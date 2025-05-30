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

package com.alibaba.polardbx.executor.handler.subhandler;

import com.alibaba.polardbx.common.Engine;
import com.alibaba.polardbx.common.exception.TddlNestableRuntimeException;
import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.common.utils.logger.LoggerFactory;
import com.alibaba.polardbx.executor.cursor.Cursor;
import com.alibaba.polardbx.executor.cursor.impl.ArrayResultCursor;
import com.alibaba.polardbx.executor.handler.VirtualViewHandler;
import com.alibaba.polardbx.gms.engine.FileStorageFilesMetaRecord;
import com.alibaba.polardbx.gms.engine.FileStorageMetaStore;
import com.alibaba.polardbx.gms.engine.FileSystemGroup;
import com.alibaba.polardbx.gms.engine.FileSystemManager;
import com.alibaba.polardbx.gms.metadb.MetaDbDataSource;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.view.InformationSchemaFileStorageFilesMeta;
import com.alibaba.polardbx.optimizer.view.VirtualView;

import java.sql.Connection;
import java.util.List;

/**
 * @author chenzilin
 */
public class InformationSchemaFileStorageFilesMetaHandler extends BaseVirtualViewSubClassHandler {

    private static final Logger logger = LoggerFactory.getLogger("oss");

    public InformationSchemaFileStorageFilesMetaHandler(VirtualViewHandler virtualViewHandler) {
        super(virtualViewHandler);
    }

    @Override
    public boolean isSupport(VirtualView virtualView) {
        return virtualView instanceof InformationSchemaFileStorageFilesMeta;
    }

    private void handleEngine(Engine engine, ExecutionContext executionContext, ArrayResultCursor cursor) {
        try (Connection conn = MetaDbDataSource.getInstance().getConnection()) {
            FileStorageMetaStore fileStorageMetaStore = new FileStorageMetaStore(engine);
            fileStorageMetaStore.setConnection(conn);
            List<FileStorageFilesMetaRecord> recordList = fileStorageMetaStore.queryFromMetaDb();
            for (FileStorageFilesMetaRecord record : recordList) {
                cursor.addRow(new Object[] {
                    record.engine,
                    record.fileName,
                    record.commitTs,
                    record.removeTs
                });
            }
        } catch (Throwable t) {
            throw new TddlNestableRuntimeException(t);
        }
    }

    @Override
    public Cursor handle(VirtualView virtualView, ExecutionContext executionContext, ArrayResultCursor cursor) {
        for (Engine engine : Engine.values()) {
            if (!Engine.isFileStore(engine)) {
                continue;
            }

            FileSystemGroup group = FileSystemManager.getFileSystemGroup(engine, false);
            if (group != null) {
                handleEngine(engine, executionContext, cursor);
            }
        }

        return cursor;
    }
}