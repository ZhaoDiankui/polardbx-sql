/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.operator.scan.impl;

import com.alibaba.polardbx.common.charset.MySQLUnicodeUtils;
import com.alibaba.polardbx.common.datatype.DecimalConverter;
import com.alibaba.polardbx.common.datatype.DecimalStructure;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.executor.chunk.BlockBuilder;
import com.alibaba.polardbx.executor.chunk.DecimalBlock;
import com.alibaba.polardbx.executor.chunk.DecimalBlockBuilder;
import com.alibaba.polardbx.executor.chunk.RandomAccessBlock;
import com.alibaba.polardbx.executor.operator.scan.AbstractColumnReader;
import com.alibaba.polardbx.executor.operator.scan.StripeLoader;
import com.alibaba.polardbx.executor.operator.scan.metrics.MetricsNameBuilder;
import com.alibaba.polardbx.executor.operator.scan.metrics.ORCMetricsWrapper;
import com.alibaba.polardbx.executor.operator.scan.metrics.ProfileKeys;
import com.alibaba.polardbx.executor.operator.scan.metrics.RuntimeMetrics;
import com.alibaba.polardbx.optimizer.core.datatype.DecimalType;
import com.codahale.metrics.Counter;
import com.google.common.base.Preconditions;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import org.apache.orc.OrcProto;
import org.apache.orc.customized.ORCProfile;
import org.apache.orc.impl.BitFieldReader;
import org.apache.orc.impl.InStream;
import org.apache.orc.impl.OrcIndex;
import org.apache.orc.impl.PositionProvider;
import org.apache.orc.impl.RecordReaderImpl;
import org.apache.orc.impl.RunLengthIntegerReaderV2;
import org.apache.orc.impl.StreamName;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.polardbx.common.datatype.DecimalTypeBase.DECIMAL_MEMORY_SIZE;

public class DecimalColumnReader extends AbstractColumnReader {
    private static final int DEFAULT_INDEX_STRIDE = 10000;

    // basic metadata
    private final StripeLoader stripeLoader;

    // in preheat mode, all row-indexes in orc-index should not be null.
    private final OrcIndex orcIndex;
    private final RuntimeMetrics metrics;

    private final boolean enableMetrics;
    // for semantic parser
    protected BitFieldReader present;
    protected InStream dataStream;
    protected RunLengthIntegerReaderV2 lengthReader;
    // open parameters
    private boolean[] rowGroupIncluded;
    private boolean await;
    // inner states
    private AtomicBoolean openFailed;
    private AtomicBoolean initializeOnlyOnce;
    private AtomicBoolean isOpened;
    // IO results
    private Throwable throwable;
    private Map<StreamName, InStream> inStreamMap;
    private CompletableFuture<Map<StreamName, InStream>> openFuture;
    // record read positions
    private int currentRowGroup;
    private int lastPosition;

    // execution time metrics.
    private Counter preparingTimer;
    private Counter seekTimer;
    private Counter parseTimer;

    public DecimalColumnReader(int columnId, boolean isPrimaryKey, StripeLoader stripeLoader, OrcIndex orcIndex,
                               RuntimeMetrics metrics, boolean enableMetrics) {
        super(columnId, isPrimaryKey);
        this.stripeLoader = stripeLoader;
        this.orcIndex = orcIndex;
        this.metrics = metrics;
        this.enableMetrics = enableMetrics;

        // inner states
        openFailed = new AtomicBoolean(false);
        initializeOnlyOnce = new AtomicBoolean(false);
        isOpened = new AtomicBoolean(false);
        throwable = null;
        inStreamMap = null;
        openFuture = null;

        // for parser
        present = null;
        dataStream = null;
        lengthReader = null;

        // read position control
        // The initial value is -1 means it must seek to the correct row group firstly.
        currentRowGroup = -1;
        lastPosition = -1;

        rowGroupIncluded = null;
        await = false;

        if (enableMetrics) {
            preparingTimer = metrics.addCounter(
                MetricsNameBuilder.columnMetricsKey(columnId, ProfileKeys.ORC_COLUMN_IO_PREPARING_TIMER),
                COLUMN_READER_TIMER,
                ProfileKeys.ORC_COLUMN_IO_PREPARING_TIMER.getProfileUnit()
            );

            seekTimer = metrics.addCounter(
                MetricsNameBuilder.columnMetricsKey(columnId, ProfileKeys.ORC_COLUMN_SEEK_TIMER),
                COLUMN_READER_TIMER,
                ProfileKeys.ORC_COLUMN_SEEK_TIMER.getProfileUnit()
            );

            parseTimer = metrics.addCounter(
                MetricsNameBuilder.columnMetricsKey(columnId, ProfileKeys.ORC_COLUMN_PARSE_TIMER),
                COLUMN_READER_TIMER,
                ProfileKeys.ORC_COLUMN_PARSE_TIMER.getProfileUnit()
            );
        }
    }

    @Override
    public boolean[] rowGroupIncluded() {
        Preconditions.checkArgument(isOpened.get());
        return rowGroupIncluded;
    }

    @Override
    public boolean isOpened() {
        return isOpened.get();
    }

    @Override
    public void open(boolean await, boolean[] rowGroupIncluded) {
        if (!isOpened.compareAndSet(false, true)) {
            throw GeneralUtil.nestedException("It's not allowed to re-open this column reader.");
        }
        this.rowGroupIncluded = rowGroupIncluded;
        this.await = await;

        // load the specified streams.
        openFuture = stripeLoader.load(columnId, rowGroupIncluded);

        if (await) {
            doWait();
        }
    }

    @Override
    public void open(CompletableFuture<Map<StreamName, InStream>> loadFuture,
                     boolean await, boolean[] rowGroupIncluded) {
        if (!isOpened.compareAndSet(false, true)) {
            throw GeneralUtil.nestedException("It's not allowed to re-open this column reader.");
        }
        this.rowGroupIncluded = rowGroupIncluded;
        this.await = await;
        this.openFuture = loadFuture;
        if (await) {
            doWait();
        }
    }

    // wait for open future and handle failure.
    private void doWait() {
        try {
            inStreamMap = openFuture.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        if (throwable != null) {
            // throw if failed.
            throw GeneralUtil.nestedException(throwable);
        }
    }

    protected void init() throws IOException {
        if (!initializeOnlyOnce.compareAndSet(false, true)) {
            return;
        }

        long start = System.nanoTime();
        if (!await) {
            doWait();
        }
        if (openFailed.get()) {
            return;
        }

        // Unlike StripePlanner in raw ORC SDK, the stream names and IO production are
        // all determined at runtime.
        StreamName presentName = new StreamName(columnId, OrcProto.Stream.Kind.PRESENT);
        StreamName dataName = new StreamName(columnId, OrcProto.Stream.Kind.DATA);
        StreamName lengthName = new StreamName(columnId, OrcProto.Stream.Kind.LENGTH);

        InStream presentStream = inStreamMap.get(presentName);
        dataStream = inStreamMap.get(dataName);
        InStream lengthStream = inStreamMap.get(lengthName);

        // initialize present and integer reader
        present = presentStream == null ? null : new BitFieldReader(presentStream);
        lengthReader = lengthStream == null ? null : new RunLengthIntegerReaderV2(lengthStream, false, true);

        // Add memory metrics.
        if (present != null) {
            String metricsName = MetricsNameBuilder.streamMetricsKey(
                presentName, ProfileKeys.ORC_STREAM_READER_MEMORY_COUNTER
            );

            ORCProfile memoryCounter = enableMetrics ? new ORCMetricsWrapper(
                metricsName,
                COLUMN_READER_MEMORY,
                ProfileKeys.ORC_STREAM_READER_MEMORY_COUNTER.getProfileUnit(),
                metrics
            ) : null;

            present.setMemoryCounter(memoryCounter);
        }

        if (lengthReader != null) {
            String metricsName = MetricsNameBuilder.streamMetricsKey(
                lengthName, ProfileKeys.ORC_STREAM_READER_MEMORY_COUNTER
            );

            ORCProfile memoryCounter = enableMetrics ? new ORCMetricsWrapper(
                metricsName,
                COLUMN_READER_MEMORY,
                ProfileKeys.ORC_STREAM_READER_MEMORY_COUNTER.getProfileUnit(),
                metrics
            ) : null;

            lengthReader.setMemoryCounter(memoryCounter);
        }

        // metrics time cost of preparing (IO waiting + data steam reader constructing)
        if (enableMetrics) {
            preparingTimer.inc(System.nanoTime() - start);
        }
    }

    @Override
    public void startAt(int rowGroupId, int elementPosition) throws IOException {
        Preconditions.checkArgument(isOpened.get());
        Preconditions.checkArgument(!openFailed.get());
        init();

        long start = System.nanoTime();
        if (rowGroupId != currentRowGroup || elementPosition < lastPosition) {
            // case 1: when given row group is different from the current group, seek to the position of it.
            // case 2: when elementPosition <= lastPosition, we need go back to the start position of this row group.
            seek(rowGroupId);

            long actualSkipRows = skipPresent(elementPosition);

            // skip on length int-reader and record the skipped length.
            long lengthToSkip = 0;
            for (int i = 0; i < actualSkipRows; ++i) {
                lengthToSkip += lengthReader.next();
            }

            // skip on data InStream
            while (dataStream != null && lengthToSkip > 0) {
                lengthToSkip -= dataStream.skip(lengthToSkip);
            }

            lastPosition = elementPosition;
            currentRowGroup = rowGroupId;
        } else if (elementPosition > lastPosition && elementPosition < DEFAULT_INDEX_STRIDE) {
            // case 3: when elementPosition > lastPosition and the group is same, just skip to given position.
            long actualSkipRows = skipPresent(elementPosition - lastPosition);

            // skip on length int-reader and record the skipped length.
            long lengthToSkip = 0;
            for (int i = 0; i < actualSkipRows; ++i) {
                lengthToSkip += lengthReader.next();
            }

            // skip on data InStream
            while (dataStream != null && lengthToSkip > 0) {
                lengthToSkip -= dataStream.skip(lengthToSkip);
            }

            lastPosition = elementPosition;
        } else if (elementPosition >= DEFAULT_INDEX_STRIDE) {
            // case 4: the position is out of range.
            throw GeneralUtil.nestedException("Invalid element position: " + elementPosition);
        }
        // case 5: the elementPosition == lastPosition and rowGroupId is equal.

        // metrics
        if (enableMetrics) {
            seekTimer.inc(System.nanoTime() - start);
        }
    }

    // Try to skip rows on present stream and count down
    // the actual rows need skipped by data stream.
    protected long skipPresent(long rows) throws IOException {
        if (present == null) {
            return rows;
        }

        long result = 0;
        for (long c = 0; c < rows; ++c) {
            // record the count of non-null values
            // in range of [current_position, current_position + rows)
            if (present.next() == 1) {
                result += 1;
            }
        }
        // It must be less than or equal to count of rows.
        return result;
    }

    @Override
    public void seek(int rowGroupId) throws IOException {
        Preconditions.checkArgument(isOpened.get());
        Preconditions.checkArgument(!openFailed.get());
        init();

        // Find the position-provider of given column and row group.
        PositionProvider positionProvider;
        OrcProto.RowIndex[] rowIndices = orcIndex.getRowGroupIndex();
        OrcProto.RowIndexEntry entry = rowIndices[columnId].getEntry(rowGroupId);
        // This is effectively a test for pre-ORC-569 files.
        if (rowGroupId == 0 && entry.getPositionsCount() == 0) {
            positionProvider = new RecordReaderImpl.ZeroPositionProvider();
        } else {
            positionProvider = new RecordReaderImpl.PositionProviderImpl(entry);
        }

        // NOTE: The order of seeking is strict!
        if (present != null) {
            present.seek(positionProvider);
        }
        if (dataStream != null) {
            dataStream.seek(positionProvider);
        }
        if (lengthReader != null) {
            lengthReader.seek(positionProvider);
        }

        currentRowGroup = rowGroupId;
        lastPosition = 0;
    }

    @Override
    public void next(RandomAccessBlock randomAccessBlock, int positionCount) throws IOException {
        Preconditions.checkArgument(isOpened.get());
        Preconditions.checkArgument(!openFailed.get());
        Preconditions.checkArgument(randomAccessBlock instanceof DecimalBlock);
        init();

        long start = System.nanoTime();

        DecimalBlock block = (DecimalBlock) randomAccessBlock;
        boolean[] nulls = block.nulls();
        Slice memorySegments = block.getMemorySegments();
        Preconditions.checkArgument(nulls != null && nulls.length == positionCount);
        DecimalType decimalType = (DecimalType) block.getType();

        if (present == null) {
            randomAccessBlock.setHasNull(false);

            if (lengthReader != null) {
                for (int i = 0; i < positionCount; i++) {
                    // no null value.
                    long length = lengthReader.next();
                    byte[] decimalBin = readDecimalBin(length);
                    int fromIndex = i * DECIMAL_MEMORY_SIZE;
                    DecimalStructure d2 = new DecimalStructure(memorySegments.slice(fromIndex, DECIMAL_MEMORY_SIZE));
                    DecimalConverter.binToDecimal(decimalBin, d2, decimalType.getPrecision(), decimalType.getScale());
                    nulls[i] = false;
                    lastPosition++;
                }
            }

        } else {
            randomAccessBlock.setHasNull(true);

            // there are some null values
            for (int i = 0; i < positionCount; i++) {
                if (present.next() != 1) {
                    // for present
                    nulls[i] = true;
                } else {
                    // if not null
                    long length = lengthReader.next();
                    byte[] decimalBin = readDecimalBin(length);
                    int fromIndex = i * DECIMAL_MEMORY_SIZE;
                    DecimalStructure d2 = new DecimalStructure(memorySegments.slice(fromIndex, DECIMAL_MEMORY_SIZE));
                    DecimalConverter.binToDecimal(decimalBin, d2, decimalType.getPrecision(), decimalType.getScale());
                    nulls[i] = false;
                }
                lastPosition++;
            }
        }

        // metrics
        if (enableMetrics) {
            parseTimer.inc(System.nanoTime() - start);
        }
    }

    private byte[] readDecimalBin(long length) throws IOException {
        int len = (int) length;
        byte[] tmp = new byte[len];
        byte[] read = new byte[len];
        int n = 0;
        while (n < len) {
            int count = dataStream.read(read, n, len - n);
            if (count < 0) {
                throw new IOException("Failed to read decimal with length: " + length);
            }
            n += count;
        }
        boolean isUtf8FromLatin1 =
            MySQLUnicodeUtils.utf8ToLatin1(read, 0, (int) length, tmp);

        if (!isUtf8FromLatin1) {
            // in columnar, decimals are stored already in latin1 encoding
            System.arraycopy(read, 0, tmp, 0, len);
        }
        return tmp;
    }

    @Override
    public void close() {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }

        // 1. Clear the resources allocated in InStream
        StreamName presentName = new StreamName(columnId, OrcProto.Stream.Kind.PRESENT);
        StreamName dataName = new StreamName(columnId, OrcProto.Stream.Kind.DATA);
        StreamName lengthName = new StreamName(columnId, OrcProto.Stream.Kind.LENGTH);

        if (inStreamMap != null) {
            InStream presentStream = inStreamMap.get(presentName);
            InStream dataStream = inStreamMap.get(dataName);
            InStream lengthStream = inStreamMap.get(lengthName);

            if (presentStream != null) {
                presentStream.close();
            }

            if (dataStream != null) {
                dataStream.close();
            }

            if (lengthStream != null) {
                lengthStream.close();
            }
        }

        // 2. Clear the memory resources held by stream
        long releasedBytes = 0L;
        releasedBytes += stripeLoader.clearStream(presentName);
        releasedBytes += stripeLoader.clearStream(dataName);
        releasedBytes += stripeLoader.clearStream(lengthName);

        if (releasedBytes > 0) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(MessageFormat.format(
                    "Release the resource of work: {0}, columnId: {1}, bytes: {2}",
                    metrics.name(), columnId, releasedBytes
                ));
            }
        }
    }
}
