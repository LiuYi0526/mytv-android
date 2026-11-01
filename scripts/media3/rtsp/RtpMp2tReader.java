/* SPDX-License-Identifier: Apache-2.0 */
package androidx.media3.exoplayer.rtsp.reader;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.extractor.ts.TsPayloadReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** MPEG-TS inside RTP. TS PTS/PCR, rather than RTP packet boundaries, define sample timing. */
@UnstableApi
public final class RtpMp2tReader implements RtpPayloadReader {
  private final TsExtractor extractor;
  private final PositionHolder positionHolder = new PositionHolder();
  private byte[] pending = new byte[0];
  private int packetSize;
  private long position;
  private int lastSequence = C.INDEX_UNSET;

  public RtpMp2tReader() {
    this(new DefaultTsPayloadReaderFactory());
  }

  public RtpMp2tReader(TsPayloadReader.Factory payloadReaderFactory) {
    extractor = new TsExtractor(TsExtractor.MODE_SINGLE_PMT,
        TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED,
        new TimestampAdjuster(0), payloadReaderFactory, TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES);
  }

  @Override public void createTracks(ExtractorOutput output, int trackId) {
    // PAT/PMT will declare the elementary tracks after PLAY starts.
    extractor.init(output);
  }

  @Override public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {}

  @Override public void seek(long nextRtpTimestamp, long timeUs) {
    pending = new byte[0];
    packetSize = 0;
    position = 0;
    lastSequence = C.INDEX_UNSET;
    extractor.seek(0, timeUs);
  }

  @Override public void consume(ParsableByteArray data, long timestamp, int sequenceNumber,
      boolean marker) throws ParserException {
    if (lastSequence != C.INDEX_UNSET && sequenceNumber != ((lastSequence + 1) & 0xffff)) {
      // Do not join a partial TS packet across an RTP loss.
      pending = new byte[0];
      packetSize = 0;
    }
    lastSequence = sequenceNumber;
    int oldLength = pending.length;
    pending = Arrays.copyOf(pending, oldLength + data.bytesLeft());
    data.readBytes(pending, oldLength, data.bytesLeft());
    ByteArrayOutputStream normalized = new ByteArrayOutputStream(pending.length);
    int offset = 0;
    while (pending.length - offset >= 188) {
      if (packetSize == 0) {
        if ((pending[offset] & 0xff) == 0x47) packetSize = 188;
        else if ((pending[offset + 4] & 0xff) == 0x47) {
          if (pending.length - offset < 192) break;
          packetSize = 192;
        }
        else { offset++; continue; }
      }
      if (pending.length - offset < packetSize) break;
      int prefix = packetSize - 188;
      if ((pending[offset + prefix] & 0xff) != 0x47) {
        packetSize = 0;
        offset++;
        continue;
      }
      normalized.write(pending, offset + prefix, 188);
      offset += packetSize;
    }
    pending = Arrays.copyOfRange(pending, offset, pending.length);
    byte[] bytes = normalized.toByteArray();
    if (bytes.length == 0) return;
    final int[] cursor = {0};
    DefaultExtractorInput input = new DefaultExtractorInput((buffer, start, length) -> {
      if (length == 0) return 0;
      if (cursor[0] == bytes.length) return C.RESULT_END_OF_INPUT;
      // Exactly one normalized TS packet per extractor call; never prefetch the next one.
      int count = Math.min(188, Math.min(length, bytes.length - cursor[0]));
      System.arraycopy(bytes, cursor[0], buffer, start, count);
      cursor[0] += count;
      return count;
    }, position, C.LENGTH_UNSET);
    try {
      long endPosition = position + bytes.length;
      while (input.getPosition() < endPosition) {
        if (extractor.read(input, positionHolder) != Extractor.RESULT_CONTINUE) {
          throw ParserException.createForMalformedContainer("Unexpected RTP TS extractor result", null);
        }
        // RTP boundaries are not EOF. An extra read at this point would flush an unfinished PES
        // (including H.264 access units) and corrupt subsequent video frames.
      }
      position = input.getPosition();
    } catch (IOException e) {
      throw ParserException.createForMalformedContainer("Invalid RTP MPEG-TS payload", e);
    }
  }
}
