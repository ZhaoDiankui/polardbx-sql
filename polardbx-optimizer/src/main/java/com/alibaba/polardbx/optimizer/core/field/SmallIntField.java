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

package com.alibaba.polardbx.optimizer.core.field;

import com.alibaba.polardbx.optimizer.config.table.charset.CollationHandlers;
import com.alibaba.polardbx.optimizer.config.table.collation.CollationHandler;
import com.alibaba.polardbx.common.datatype.Decimal;
import com.alibaba.polardbx.common.type.MySQLStandardFieldType;
import com.alibaba.polardbx.common.utils.XxhashUtils;
import com.alibaba.polardbx.common.utils.time.MySQLTimeConverter;
import com.alibaba.polardbx.common.utils.time.MySQLTimeTypeUtil;
import com.alibaba.polardbx.common.utils.time.core.MysqlDateTime;
import com.alibaba.polardbx.common.utils.time.core.OriginalTemporalValue;
import com.alibaba.polardbx.common.utils.time.parser.NumericTimeParser;
import com.alibaba.polardbx.common.utils.time.parser.StringNumericParser;
import com.alibaba.polardbx.optimizer.config.table.charset.CharsetHandler;
import com.alibaba.polardbx.optimizer.core.datatype.DataType;
import com.alibaba.polardbx.rpc.result.XResult;
import com.google.common.primitives.UnsignedLongs;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.mysql.cj.polarx.protobuf.PolarxResultset;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.slice.XxHash64;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Arrays;

/**
 * MySQL small int data type (16bit)
 */
public class SmallIntField extends AbstractNumericField {
    public static final Decimal DECIMAL_INT_16_MAX_VALUE = Decimal.fromLong(INT_16_MAX);
    public static final Decimal DECIMAL_INT_16_MIN_VALUE = Decimal.fromLong(INT_16_MIN);
    public static final Decimal DECIMAL_UNSIGNED_INT_16_MAX_VALUE =
        Decimal.fromString(Integer.toUnsignedString(UNSIGNED_INT_16_MAX));
    public static final Decimal DECIMAL_UNSIGNED_INT_16_MIN_VALUE =
        Decimal.fromString(Integer.toUnsignedString(UNSIGNED_INT_16_MIN));
    private static final int INT_16_BYTES = 2;
    final boolean isUnsigned;
    private byte[] packedBinary;

    public SmallIntField(DataType<?> fieldType) {
        super(fieldType);
        packedBinary = new byte[INT_16_BYTES];
        isUnsigned = fieldType.isUnsigned();
    }

    @Override
    protected TypeConversionStatus storeXProtocolInternal(XResult xResult, PolarxResultset.ColumnMetaData meta,
                                                          ByteString byteString, int columnIndex,
                                                          SessionProperties sessionProperties) throws Exception {
        final byte[] rawBytes = byteString.toByteArray();
        final CodedInputStream stream = CodedInputStream.newInstance(rawBytes);
        if (!isUnsigned) {
            short val = (short) getU64(meta.getType(), stream);
            return storeLong(val & LONG_MASK, false);
        } else {
            int val = (int) getU64(meta.getType(), stream);
            return storeLong(val & LONG_MASK, true);
        }
    }

    @Override
    protected TypeConversionStatus storeJdbcInternal(ResultSet rs, int columnIndex, SessionProperties sessionProperties)
        throws Exception {
        if (!isUnsigned) {
            short val = rs.getShort(columnIndex);
            if (val != 0 || !rs.wasNull()) {
                return storeLong(val & LONG_MASK, false);
            } else {
                setNull();
                return TypeConversionStatus.TYPE_OK;
            }
        } else {
            int val = rs.getInt(columnIndex);
            if (val != 0 || !rs.wasNull()) {
                return storeLong(val & LONG_MASK, true);
            } else {
                setNull();
                return TypeConversionStatus.TYPE_OK;
            }
        }
    }

    @Override
    protected TypeConversionStatus storeInternal(Object value, DataType<?> resultType,
                                                 SessionProperties sessionProperties) {
        int fromSqlType = resultType.getSqlType();
        switch (resultType.fieldType()) {
        case MYSQL_TYPE_TINY:
        case MYSQL_TYPE_SHORT:
        case MYSQL_TYPE_INT24:
        case MYSQL_TYPE_LONG:
        case MYSQL_TYPE_LONGLONG:
            if (value instanceof BigDecimal) {
                return storeString(((BigDecimal) value).toPlainString(), sessionProperties);
            } else if (value instanceof BigInteger) {
                BigInteger bigInteger = (BigInteger) value;

                // The boundary value of bigint is min value of longlong and max value of ulonglong.
                // otherwise, the big integer number will be recognized as decimal value.
                if (bigInteger.compareTo(MAX_UNSIGNED_INT64) > 0 || bigInteger.compareTo(MIN_SIGNED_INT64) < 0) {
                    BigDecimal decimalNumber = new BigDecimal(bigInteger);
                    return storeBigDecimal(decimalNumber);
                } else {
                    return storeString(bigInteger.toString(), sessionProperties);
                }
            } else if (value instanceof Number) {
                boolean isDataTypeUnsigned = resultType.isUnsigned();
                long longValue = ((Number) value).longValue();
                return storeLong(longValue, isDataTypeUnsigned);
            } else if (value == null) {
                setNull();
                return TypeConversionStatus.TYPE_OK;
            }
            break;
        case MYSQL_TYPE_DOUBLE:
        case MYSQL_TYPE_FLOAT:
            if (value instanceof Number) {
                double doubleValue = ((Number) value).doubleValue();
                return storeDouble(doubleValue);
            } else if (value == null) {
                setNull();
                return TypeConversionStatus.TYPE_OK;
            }
            break;
        case MYSQL_TYPE_DECIMAL:
        case MYSQL_TYPE_NEWDECIMAL:
            if (value instanceof Decimal) {
                return storeDecimal((Decimal) value);
            } else if (value instanceof BigDecimal) {
                return storeBigDecimal((BigDecimal) value);
            } else if (value == null) {
                setNull();
                return TypeConversionStatus.TYPE_OK;
            }
            break;
        case MYSQL_TYPE_DATE:
        case MYSQL_TYPE_NEWDATE:
        case MYSQL_TYPE_DATETIME:
        case MYSQL_TYPE_DATETIME2:
        case MYSQL_TYPE_TIMESTAMP:
        case MYSQL_TYPE_TIMESTAMP2:
        case MYSQL_TYPE_TIME:
        case MYSQL_TYPE_TIME2:
            if (value instanceof OriginalTemporalValue) {
                // store value from chunk executor as mysql datetime.
                OriginalTemporalValue temporalValue = (OriginalTemporalValue) value;
                MysqlDateTime mysqlDateTime = temporalValue.getMysqlDateTime();
                Number numericRes =
                    MySQLTimeConverter.convertTemporalToNumeric(mysqlDateTime, fromSqlType, Types.SMALLINT);
                long longValue = numericRes.longValue();
                return storeLong(mysqlDateTime.isNeg() ? -longValue : longValue, false);
            } else if (value == null) {
                setNull();
                return TypeConversionStatus.TYPE_OK;
            }
            break;
        case MYSQL_TYPE_VAR_STRING:
        case MYSQL_TYPE_STRING:
            // must be in UTF-8 character set.
            if (value instanceof Slice) {
                return storeSlice((Slice) value, sessionProperties);
            } else if (value instanceof String) {
                return storeString((String) value, sessionProperties);
            } else if (value instanceof byte[]) {
                return storeBytes((byte[]) value, sessionProperties);
            } else if (value == null) {
                setNull();
                return TypeConversionStatus.TYPE_OK;
            }
            break;
        default:
            return TypeConversionStatus.TYPE_ERR_UNSUPPORTED_IMPLICIT_CAST;
        }
        return TypeConversionStatus.TYPE_ERR_UNSUPPORTED_IMPLICIT_CAST;
    }

    private TypeConversionStatus storeLong(long l, boolean isDataTypeUnsigned) {
        TypeConversionStatus conversionStatus = TypeConversionStatus.TYPE_OK;
        short res;
        if (isUnsigned) {
            if (l < 0 && !isDataTypeUnsigned) {
                res = 0;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else if (UnsignedLongs.compare(l, ((long) UNSIGNED_INT_16_MAX) & 0xFFFFFFFFL) > 0) {
                res = (short) UNSIGNED_INT_16_MAX;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else {
                res = (short) l;
            }
        } else {
            if (l < 0 && isDataTypeUnsigned) {
                // generate overflow
                l = UNSIGNED_INT_16_MAX + 1;
            }
            if (l < (long) INT_16_MIN) {
                res = (short) INT_16_MIN;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else if (l > (long) INT_16_MAX) {
                res = (short) INT_16_MAX;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else {
                res = (short) l;
            }
        }
        storeAsBinary(res);
        return conversionStatus;
    }

    private TypeConversionStatus storeDouble(double d) {
        TypeConversionStatus conversionStatus = TypeConversionStatus.TYPE_OK;
        short res;
        d = Math.rint(d);
        if (isUnsigned) {
            if (d < 0) {
                res = 0;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else if (d >= (double) UNSIGNED_INT_16_MAX) {
                res = (short) UNSIGNED_INT_16_MAX;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else {
                res = (short) d;
            }
        } else {
            if (d < (double) INT_16_MIN) {
                res = (short) INT_16_MIN;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else if (d > (double) INT_16_MAX) {
                res = (short) INT_16_MAX;
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
            } else {
                res = (short) d;
            }
        }
        storeAsBinary(res);
        return conversionStatus;
    }

    /**
     * Conversion from decimal to longlong. Checks overflow and returns
     * correct value (min/max) in case of overflow.
     */
    private TypeConversionStatus storeDecimal(Decimal decimal) {
        TypeConversionStatus conversionStatus = TypeConversionStatus.TYPE_OK;
        boolean hasOverflow = false;
        short res = 0;

        if (isUnsigned) {
            // check overflow for unsigned long.
            if (decimal.compareTo(DECIMAL_UNSIGNED_INT_16_MIN_VALUE) < 0) {
                hasOverflow = true;
                res = (short) UNSIGNED_INT_16_MIN;
            } else if (decimal.compareTo(DECIMAL_UNSIGNED_INT_16_MAX_VALUE) > 0) {
                hasOverflow = true;
                res = (short) UNSIGNED_INT_16_MAX;
            }
        } else {
            // check overflow for signed long.
            if (decimal.compareTo(DECIMAL_INT_16_MIN_VALUE) < 0) {
                hasOverflow = true;
                res = (short) INT_16_MIN;
            } else if (decimal.compareTo(DECIMAL_INT_16_MAX_VALUE) > 0) {
                hasOverflow = true;
                res = (short) INT_16_MAX;
            }
        }

        if (hasOverflow) {
            conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
        } else {
            // todo should integrate with new decimal type system.
            res = decimal.toBigDecimal().setScale(0, RoundingMode.HALF_UP).shortValue();
        }
        storeAsBinary(res);

        return conversionStatus;
    }

    private TypeConversionStatus storeBigDecimal(BigDecimal decimal) {
        TypeConversionStatus conversionStatus = TypeConversionStatus.TYPE_OK;
        boolean hasOverflow = false;
        short res = 0;

        if (isUnsigned) {
            // check overflow for unsigned long.
            if (decimal.compareTo(DECIMAL_UNSIGNED_INT_16_MIN_VALUE.toBigDecimal()) < 0) {
                hasOverflow = true;
                res = (short) UNSIGNED_INT_16_MIN;
            } else if (decimal.compareTo(DECIMAL_UNSIGNED_INT_16_MAX_VALUE.toBigDecimal()) > 0) {
                hasOverflow = true;
                res = (short) UNSIGNED_INT_16_MAX;
            }
        } else {
            // check overflow for signed long.
            if (decimal.compareTo(DECIMAL_INT_16_MIN_VALUE.toBigDecimal()) < 0) {
                hasOverflow = true;
                res = (short) INT_16_MIN;
            } else if (decimal.compareTo(DECIMAL_INT_16_MAX_VALUE.toBigDecimal()) > 0) {
                hasOverflow = true;
                res = (short) INT_16_MAX;
            }
        }

        if (hasOverflow) {
            conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
        } else {
            // todo should integrate with new decimal type system.
            res = decimal.setScale(0, RoundingMode.HALF_UP).shortValue();
        }
        storeAsBinary(res);

        return conversionStatus;
    }

    private TypeConversionStatus storeString(String str, SessionProperties sessionProperties) {
        return storeBytes(str.getBytes(), sessionProperties);
    }

    private TypeConversionStatus storeSlice(Slice slice, SessionProperties sessionProperties) {
        Object base = slice.getBase();
        byte[] rawBytes;
        if (base instanceof byte[]) {
            // avoid to copy.
            rawBytes = (byte[]) base;
        } else {
            // need to copy.
            rawBytes = slice.getBytes();
        }
        return storeBytes(rawBytes, sessionProperties);
    }

    private TypeConversionStatus storeBytes(byte[] bytes, SessionProperties sessionProperties) {
        TypeConversionStatus conversionStatus = TypeConversionStatus.TYPE_OK;

        // parse by charset-specific string numeric parser.
        long[] result = new long[3];
        CharsetHandler charsetHandler = getCharsetHandler();
        charsetHandler.parseToLongWithRound(bytes, 0, bytes.length, result, isUnsigned);
        long rounded = result[StringNumericParser.NUMERIC_INDEX];
        long parseError = result[StringNumericParser.ERROR_INDEX];
        int numberEnd = (int) result[StringNumericParser.POSITION_INDEX];

        if (isUnsigned) {
            if ((UnsignedLongs.compare(rounded, ((long) UNSIGNED_INT_16_MAX) & 0xFFFFFFFFL) > 0
                && (rounded = UNSIGNED_INT_16_MAX) != 0)
                || parseError == StringNumericParser.MY_ERRNO_ERANGE) {
                // out of range
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
                storeAsBinary((short) rounded);
                return conversionStatus;
            }
        } else {
            if (rounded < INT_16_MIN) {
                rounded = INT_16_MIN;
                // out of range
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
                storeAsBinary((short) rounded);
                return conversionStatus;
            } else if (rounded > INT_16_MAX) {
                rounded = INT_16_MAX;
                // out of range
                conversionStatus = TypeConversionStatus.TYPE_WARN_OUT_OF_RANGE;
                storeAsBinary((short) rounded);
                return conversionStatus;
            }
        }
        // check int
        if (sessionProperties.getCheckLevel() != FieldCheckLevel.CHECK_FIELD_IGNORE) {
            // for non-ignorable level(like insert / update), check int
            conversionStatus = checkInt(bytes, parseError, numberEnd);
        }

        storeAsBinary((short) rounded);
        return conversionStatus;
    }

    private TypeConversionStatus storeAsBinary(short nr) {
        packedBinary[0] = (byte) (nr & 0xFF);
        packedBinary[1] = (byte) ((nr >> 8) & 0xFF);
        return TypeConversionStatus.TYPE_OK;
    }

    @Override
    public long longValue(SessionProperties sessionProperties) {
        if (isUnsigned) {
            int x = (((((((int) packedBinary[1]) & 0xFF)) << 8) |
                (((int) packedBinary[0]) & 0xFF)));
            return ((short) x) & 0xFFFF;
        } else {
            return ((packedBinary[1]) & 128) != 0 ?
                (((((0xFFFF) << 16) |
                    ((((int) packedBinary[1]) & 0xFF)) << 8) |
                    (((int) packedBinary[0]) & 0xFF))) :
                (((((((int) packedBinary[1]) & 0xFF)) << 8) |
                    (((int) packedBinary[0]) & 0xFF)));
        }
    }

    @Override
    public Slice stringValue(SessionProperties sessionProperties) {
        String numberStr = String.valueOf(longValue());
        return Slices.utf8Slice(numberStr);
    }

    @Override
    public MysqlDateTime datetimeValue(int timeParseFlags, SessionProperties sessionProperties) {
        long longValue = longValue();
        MysqlDateTime t =
            NumericTimeParser.parseNumeric(longValue, MySQLTimeTypeUtil.DATETIME_SQL_TYPE, timeParseFlags);
        return t;
    }

    @Override
    public MysqlDateTime timeValue(int timeParseFlags, SessionProperties sessionProperties) {
        long longValue = longValue();
        MysqlDateTime t = NumericTimeParser.parseNumeric(longValue, Types.TIME, timeParseFlags);
        return t;
    }

    @Override
    public byte[] rawBytes() {
        return packedBinary;
    }

    @Override
    int calPacketLength() {
        return INT_16_BYTES;
    }

    @Override
    public MySQLStandardFieldType standardFieldType() {
        return MySQLStandardFieldType.MYSQL_TYPE_SHORT;
    }

    @Override
    public CollationHandler getCollationHandler() {
        return CollationHandlers.COLLATION_HANDLER_LATIN1_SWEDISH_CI;
    }

    @Override
    public void reset() {
        Arrays.fill(packedBinary, (byte) 0);
        isNull = false;
    }

    @Override
    public void setNull() {
        reset();
        isNull = true;
    }

    @Override
    public void hash(long[] numbers) {
        long nr1 = numbers[0];
        long nr2 = numbers[1];
        if (isNull()) {
            nr1 ^= (nr1 << 1) | 1;
            numbers[0] = nr1;
        } else {
            int length = packetLength();
            CollationHandler collationHandler = getCollationHandler();
            collationHandler.hashcode(packedBinary, length, numbers);
        }
    }

    @Override
    public long xxHashCode() {
        if (isNull()) {
            return NULL_HASH_CODE;
        }
        return XxhashUtils.finalShuffle(longValue());
    }

    @Override
    public void makeSortKey(byte[] result, int len) {
        if (isUnsigned) {
            result[0] = packedBinary[0];
        } else {
            // Revers signbit
            result[0] = (byte) (packedBinary[0] ^ 128);
        }
        result[1] = packedBinary[1];
    }
}
