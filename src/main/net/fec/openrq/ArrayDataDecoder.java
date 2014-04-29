/*
 * Copyright 2014 Jose Lopes
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.fec.openrq;


import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import net.fec.openrq.DataUtils.SourceBlockSupplier;
import net.fec.openrq.decoder.DataDecoder;
import net.fec.openrq.decoder.SourceBlockDecoder;
import net.fec.openrq.parameters.FECParameters;
import net.fec.openrq.parameters.ParameterChecker;
import net.fec.openrq.parameters.ParameterIO;
import net.fec.openrq.util.arithmetic.ExtraMath;
import net.fec.openrq.util.array.ArrayUtils;
import net.fec.openrq.util.collection.ImmutableList;
import net.fec.openrq.util.numericaltype.SizeOf;


/**
 * A RaptorQ decoder for an array data object.
 */
public final class ArrayDataDecoder implements DataDecoder {

    static ArrayDataDecoder newDecoder(FECParameters fecParams, int extraSymbols) {

        if (fecParams.dataLength() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximum data length exceeded");
        }
        if (extraSymbols < 0) {
            throw new IllegalArgumentException("negative number of extra symbols");
        }

        final byte[] dataArray = new byte[(int)fecParams.dataLength()];
        return new ArrayDataDecoder(dataArray, fecParams, extraSymbols);
    }


    private final byte[] dataArray;
    private final FECParameters fecParams;
    private final ImmutableList<ArraySourceBlockDecoder> srcBlockDecoders;


    private ArrayDataDecoder(byte[] dataArray, FECParameters fecParams, final int extraSymbols) {

        this.dataArray = dataArray;
        this.fecParams = fecParams;
        this.srcBlockDecoders = DataUtils.partitionData(
            ArraySourceBlockDecoder.class,
            fecParams,
            new SourceBlockSupplier<ArraySourceBlockDecoder>() {

                @Override
                public ArraySourceBlockDecoder get(int off, int sbn, int K) {

                    return ArraySourceBlockDecoder.newDecoder(
                        ArrayDataDecoder.this, ArrayDataDecoder.this.dataArray, off,
                        ArrayDataDecoder.this.fecParams,
                        sbn, K, extraSymbols);
                }
            });
    }

    @Override
    public FECParameters fecParameters() {

        return fecParams;
    }

    @Override
    public long dataLength() {

        return fecParams.dataLength();
    }

    @Override
    public int symbolSize() {

        return fecParams.symbolSize();
    }

    @Override
    public int numberOfSourceBlocks() {

        return fecParams.numberOfSourceBlocks();
    }

    @Override
    public boolean isDataDecoded() {

        for (SourceBlockDecoder dec : srcBlockDecoders) {
            if (!dec.isSourceBlockDecoded()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public SourceBlockDecoder decoderForSourceBlock(int sbn) {

        if (sbn < 0 || sbn >= srcBlockDecoders.size()) {
            throw new IllegalArgumentException("invalid source block number");
        }

        return srcBlockDecoders.get(sbn); // list is random access
    }

    /**
     * Returns an array of bytes containing the source data. Use method {@link #isDataDecoded()} to check if the data is
     * complete.
     * 
     * @return an array of bytes containing the source data
     * @see #isDataDecoded()
     */
    public byte[] dataArray() {

        return dataArray;
    }

    @Override
    public Parsed<EncodingPacket> parsePacket(int sbn, int esi, byte[] symbols, boolean copySymbols) {

        return parsePacket(sbn, esi, symbols, 0, symbols.length, copySymbols);
    }

    @Override
    public Parsed<EncodingPacket> parsePacket(int sbn, int esi, byte[] symbols, int off, int len, boolean copySymbols) {

        ArrayUtils.checkArrayBounds(off, len, symbols.length);
        return parsePacket(sbn, esi, ByteBuffer.wrap(symbols, off, len), copySymbols);
    }

    @Override
    public Parsed<EncodingPacket> parsePacket(int sbn, int esi, ByteBuffer symbols, boolean copySymbols) {

        return parsePacket(sbn, esi, symbols, symbols.remaining(), copySymbols);
    }

    @Override
    public Parsed<EncodingPacket> parsePacket(SerializablePacket ser, boolean copySymbols) {

        return parsePacket(ser.sourceBlockNumber(), ser.encodingSymbolID(), ser.symbols(), copySymbols);
    }

    @Override
    public Parsed<EncodingPacket> parsePacket(byte[] array, boolean copySymbols) {

        return parsePacket(array, 0, array.length, copySymbols);
    }

    @Override
    public Parsed<EncodingPacket> parsePacket(byte[] array, int off, int len, boolean copySymbols) {

        ArrayUtils.checkArrayBounds(off, len, array.length);
        return parsePacket(ByteBuffer.wrap(array, off, len), copySymbols);
    }

    @Override
    public Parsed<EncodingPacket> parsePacket(ByteBuffer buffer, boolean copySymbols) {

        if (buffer.remaining() < SizeOf.INT) return Parsed.invalid("FEC Payload ID is missing");
        final int fecPayloadID = buffer.getInt();

        if (buffer.remaining() < SizeOf.INT) return Parsed.invalid("size of symbols data is missing");
        final int symbLen = buffer.getInt();
        if (symbLen <= 0) return Parsed.invalid("size of symbols data is non-positive");

        final int rem = buffer.remaining();
        if (rem < symbLen) {
            return Parsed.invalid(String.format(
                "symbols data is incomplete, required %d bytes but only %d bytes are available", symbLen, rem));
        }

        final int sbn = ParameterIO.extractSourceBlockNumber(fecPayloadID);
        final int esi = ParameterIO.extractEncodingSymbolID(fecPayloadID);
        return parsePacket(sbn, esi, buffer, symbLen, copySymbols);
    }

    @Override
    public Parsed<EncodingPacket> readPacketFrom(DataInput in) throws IOException {

        final int fecPayloadID = in.readInt();
        final int symbLen = in.readInt();
        if (symbLen <= 0) return Parsed.invalid("size of symbols data is non-positive");

        final byte[] symbols = new byte[symbLen];
        in.readFully(symbols);

        final int sbn = ParameterIO.extractSourceBlockNumber(fecPayloadID);
        final int esi = ParameterIO.extractEncodingSymbolID(fecPayloadID);
        return parsePacket(sbn, esi, symbols, false);
    }

    @Override
    public Parsed<EncodingPacket> readPacketFrom(ReadableByteChannel ch) throws IOException {

        final ByteBuffer intsBuf = ByteBuffer.allocate(SizeOf.INT + SizeOf.INT);
        while (intsBuf.hasRemaining()) {
            ch.read(intsBuf);
        }
        intsBuf.flip();

        final int fecPayloadID = intsBuf.getInt();
        final int symbLen = intsBuf.getInt();
        if (symbLen <= 0) return Parsed.invalid("size of symbols data is non-positive");

        final ByteBuffer symbols = ByteBuffer.allocate(symbLen);
        while (symbols.hasRemaining()) {
            ch.read(symbols);
        }
        symbols.flip();

        final int sbn = ParameterIO.extractSourceBlockNumber(fecPayloadID);
        final int esi = ParameterIO.extractEncodingSymbolID(fecPayloadID);
        return parsePacket(sbn, esi, symbols, false);
    }

    // requires valid symbLen
    private Parsed<EncodingPacket> parsePacket(int sbn, int esi, ByteBuffer symbols, int symbLen, boolean copySymbols) {

        if (!ParameterChecker.isValidFECPayloadID(sbn, esi, numberOfSourceBlocks())) {
            return Parsed.invalid(ParameterChecker.testFECPayloadID(sbn, esi, numberOfSourceBlocks()));
        }

        final int T = symbolSize();
        final int K = srcBlockDecoders.get(sbn).numberOfSourceSymbols();
        final int numSymbols = ExtraMath.ceilDiv(symbLen, T); // account for smaller last symbol
        if (numSymbols == 0) {
            return Parsed.invalid("there is no symbols data");
        }

        if (esi < K) { // source symbols
            if (numSymbols <= K - esi) {
                return Parsed.of(EncodingPacket.newSourcePacket(
                    sbn, esi, getSymbolData(symbols, symbLen, copySymbols), numSymbols));
            }
            else {
                return Parsed.invalid(String.format(
                    "an ESI of %d requires a number of source symbols (%d) of at most %d",
                    esi, numSymbols, K - esi));
            }
        }
        else { // repair symbols
            final int maxESI = ParameterChecker.maxEncodingSymbolID();
            if (numSymbols <= (1 + maxESI - esi)) {
                return Parsed.of(EncodingPacket.newRepairPacket(
                    sbn, esi, getSymbolData(symbols, symbLen, copySymbols), numSymbols));
            }
            else {
                return Parsed.invalid(String.format(
                    "an ESI of %d requires a number of repair symbols (%d) of at most %d",
                    esi, numSymbols, 1 + maxESI - esi));
            }
        }
    }

    // requires valid symbolsLen
    private static ByteBuffer getSymbolData(ByteBuffer symbols, int symbLen, boolean copySymbols) {

        if (copySymbols) {
            final ByteBuffer copy = ByteBuffer.allocate(symbLen);
            copy.put(symbols); // advances both buffer positions
            copy.flip();
            return copy;
        }
        else {
            final int prevLim = symbols.limit();
            final int sliceLim = symbols.position() + symbLen;

            // prepare slice but restore the limit afterwards
            symbols.limit(sliceLim);
            final ByteBuffer slice = symbols.slice();
            symbols.limit(prevLim);

            symbols.position(sliceLim); // advance the position
            return slice;
        }
    }
}
