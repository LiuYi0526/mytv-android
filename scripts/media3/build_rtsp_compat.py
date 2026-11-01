"""Rebuild the MP2T compatibility classes in the official Media3 1.11.1 RTSP AAR.

Offline, hash-pinned inputs. Unmodified classes and all upstream licenses are preserved.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

PACKAGE = 'androidx/media3/exoplayer/rtsp/'
AAR_SHA256 = 'd7585ab89e958041f803caf4cbd466077289eb7cd3a6d92ddc64f6a727872575'
SOURCES_SHA256 = '492f336e1e6e7b0c43f1ff5c08bb4718e61b7ba84ad1cb14b8db131db9dd99d6'
TS_FACTORY = 'androidx.media3.extractor.ts.TsPayloadReader.Factory'
DEFAULT_TS_FACTORY = 'new androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory()'


def replace(text, old, new, count=1):
    if text.count(old) != count:
        raise ValueError(f'Upstream patch context changed: {old[:100]!r}')
    return text.replace(old, new)


def patches(sources):
    out = {}
    def read(name):
        return sources.read(PACKAGE + name + '.java').decode('utf-8')
    s = read('MediaDescription')
    s = replace(s, '        case RTP_STATIC_PAYLOAD_TYPE_PCMU:',
        '        case 33:\n          return "33 MP2T/90000";\n        case RTP_STATIC_PAYLOAD_TYPE_PCMU:')
    out['MediaDescription'] = s
    s = read('RtpPayloadFormat')
    s = replace(s, '      case RTP_MEDIA_AC3:\n', '      case "MP2T":\n      case RTP_MEDIA_AC3:\n', 2)
    s = replace(s, '      case "MP2T":\n      case RTP_MEDIA_AC3:\n        return MimeTypes.AUDIO_AC3;',
        '      case "MP2T":\n        return MimeTypes.VIDEO_MP2T;\n      case RTP_MEDIA_AC3:\n        return MimeTypes.AUDIO_AC3;')
    out['RtpPayloadFormat'] = s
    s = read('RtspMediaTrack')
    s = replace(s, '    checkArgument(\n        mediaDescription.attributes.containsKey(ATTR_CONTROL), "missing attribute control");\n', '')
    s = replace(s, 'castNonNull(mediaDescription.attributes.get(ATTR_CONTROL))',
        'mediaDescription.attributes.containsKey(ATTR_CONTROL)\n                ? castNonNull(mediaDescription.attributes.get(ATTR_CONTROL)) : GENERIC_CONTROL_ATTR')
    out['RtspMediaTrack'] = s
    s = read('reader/DefaultRtpPayloadReaderFactory')
    s = replace(s, '      case MimeTypes.AUDIO_AC3:',
        '      case MimeTypes.VIDEO_MP2T:\n        return new RtpMp2tReader();\n      case MimeTypes.AUDIO_AC3:')
    out['reader/DefaultRtpPayloadReaderFactory'] = s
    s = read('RtpExtractor')
    s = replace(s, '  private boolean firstPacketRead;', '  private volatile boolean firstPacketRead;')
    s = replace(s, '    this.trackId = trackId;', f'''    this(payloadFormat, trackId, {DEFAULT_TS_FACTORY});
  }}

  RtpExtractor(RtpPayloadFormat payloadFormat, int trackId, {TS_FACTORY} tsFactory) {{
    this.trackId = trackId;''')
    s = replace(s, '        checkNotNull(new DefaultRtpPayloadReaderFactory().createPayloadReader(payloadFormat));',
        '''        "MP2T".equalsIgnoreCase(payloadFormat.mediaEncoding)
            ? new androidx.media3.exoplayer.rtsp.reader.RtpMp2tReader(tsFactory)
            : checkNotNull(new DefaultRtpPayloadReaderFactory().createPayloadReader(payloadFormat));''')
    s = replace(s, '    output.endTracks();', '''    if (!(payloadReader instanceof androidx.media3.exoplayer.rtsp.reader.RtpMp2tReader)) {
      output.endTracks();
    }''')
    out['RtpExtractor'] = s
    s = read('RtpDataLoadable')
    s = replace(s, '  private @MonotonicNonNull RtpExtractor extractor;',
        '  private volatile @MonotonicNonNull RtpExtractor extractor;')
    s = replace(s, '  public void cancelLoad() {', '''  public boolean hasReceivedRtpPacket() {
    @Nullable RtpExtractor current = extractor;
    return current != null && current.hasReadFirstRtpPacket();
  }

  @Override
  public void cancelLoad() {''')
    # Move the existing @Override from the new query back onto cancelLoad.
    s = replace(s, '  @Override\n  public boolean hasReceivedRtpPacket()', '  public boolean hasReceivedRtpPacket()')
    s = replace(s, '  private final RtpDataChannel.Factory rtpDataChannelFactory;',
        f'  private final RtpDataChannel.Factory rtpDataChannelFactory;\n  private final {TS_FACTORY} tsFactory;')
    s = replace(s, '      RtpDataChannel.Factory rtpDataChannelFactory) {',
        f'      RtpDataChannel.Factory rtpDataChannelFactory, {TS_FACTORY} tsFactory) {{')
    s = replace(s, '    this.rtpDataChannelFactory = rtpDataChannelFactory;',
        '    this.rtpDataChannelFactory = rtpDataChannelFactory;\n    this.tsFactory = tsFactory;')
    s = replace(s, 'new RtpExtractor(rtspMediaTrack.payloadFormat, trackId)',
        'new RtpExtractor(rtspMediaTrack.payloadFormat, trackId, tsFactory)')
    out['RtpDataLoadable'] = s
    s = read('RtspMediaSource')
    s = replace(s, '    private boolean debugLoggingEnabled;',
        f'    private boolean debugLoggingEnabled;\n    private {TS_FACTORY} tsFactory = {DEFAULT_TS_FACTORY};')
    s = replace(s, '    public Factory() {', f'''    /** Sets TS elementary-stream readers for MP2T (for example AV3A). */
    @CanIgnoreReturnValue
    public Factory setTsPayloadReaderFactory({TS_FACTORY} factory) {{
      tsFactory = checkNotNull(factory);
      return this;
    }}

    public Factory() {{''')
    s = replace(s, '          debugLoggingEnabled);', '          debugLoggingEnabled, tsFactory, Math.min(timeoutMs, 1500));')
    s = replace(s, '  private final boolean debugLoggingEnabled;',
        f'  private final boolean debugLoggingEnabled;\n  private final {TS_FACTORY} tsFactory;\n  private final long udpProbeTimeoutMs;')
    s = replace(s, '      boolean debugLoggingEnabled) {',
        f'      boolean debugLoggingEnabled, {TS_FACTORY} tsFactory, long udpProbeTimeoutMs) {{')
    s = replace(s, '    this.debugLoggingEnabled = debugLoggingEnabled;\n    this.timelineDurationUs',
        '    this.debugLoggingEnabled = debugLoggingEnabled;\n    this.tsFactory = tsFactory;\n    this.udpProbeTimeoutMs = udpProbeTimeoutMs;\n    this.timelineDurationUs')
    s = replace(s, '        debugLoggingEnabled);', '        debugLoggingEnabled, tsFactory, udpProbeTimeoutMs);')
    out['RtspMediaSource'] = s
    s = read('RtspClient')
    s = replace(s, '  private boolean receivedAuthorizationRequest;',
        '  private boolean receivedAuthorizationRequest;\n  private int redirectCount;')
    s = replace(s, '              RtspClient.this.uri = Uri.parse(redirectionUriString);', '''              if (++redirectCount > 5) {
                dispatchRtspError(new RtspPlaybackException("Too many RTSP redirects"));
                return;
              }
              Uri nextUri = androidx.media3.common.util.UriUtil.resolveToUri(uri.toString(), redirectionUriString);
              if (!"rtsp".equalsIgnoreCase(nextUri.getScheme()) || nextUri.getHost() == null) {
                dispatchRtspError(new RtspPlaybackException("Invalid RTSP redirect"));
                return;
              }
              boolean sameServer = java.util.Objects.equals(uri.getHost(), nextUri.getHost())
                  && (uri.getPort() > 0 ? uri.getPort() : DEFAULT_RTSP_PORT)
                      == (nextUri.getPort() > 0 ? nextUri.getPort() : DEFAULT_RTSP_PORT);
              try {
                // Location can point at a different IPTV server. Changing only the request URI
                // leaves DESCRIBE on the old socket, where these servers never answer it.
                messageChannel.close();
                messageChannel = new RtspMessageChannel(new MessageListener());
                messageChannel.open(getSocket(nextUri));
              } catch (IOException e) {
                Util.closeQuietly(messageChannel);
                dispatchRtspError(new RtspPlaybackException(e));
                return;
              }
              pendingRequests.clear();
              sessionId = null;
              receivedAuthorizationRequest = false;
              rtspAuthenticationInfo = null;
              if (!sameServer) rtspAuthUserInfo = null;
              RtspClient.this.uri = RtspMessageUtil.removeUserInfo(nextUri);''')
    s = replace(s, 'RtspMessageUtil.parseUserInfo(RtspClient.this.uri);', 'RtspMessageUtil.parseUserInfo(nextUri);')
    out['RtspClient'] = s
    s = read('RtspMediaPeriod')
    s = replace(s, '  private final Allocator allocator;', f'  private final Allocator allocator;\n  private final {TS_FACTORY} tsFactory;\n  private final long udpProbeTimeoutMs;\n  private final Runnable udpProbeTimeout = this::retryIfNoUdpPackets;')
    s = replace(s, '      boolean debugLoggingEnabled) {', f'      boolean debugLoggingEnabled, {TS_FACTORY} tsFactory, long udpProbeTimeoutMs) {{')
    s = replace(s, '    this.allocator = allocator;', '    this.allocator = allocator;\n    this.tsFactory = tsFactory;\n    this.udpProbeTimeoutMs = udpProbeTimeoutMs;')
    s = replace(s, '  public void release() {\n    for (int i = 0;',
        '  public void release() {\n    handler.removeCallbacks(udpProbeTimeout);\n    for (int i = 0;')
    s = replace(s, '  private void retryWithRtpTcp() {', '''  private void retryIfNoUdpPackets() {
    if (released || isUsingRtpTcp) return;
    boolean active = false;
    for (RtspLoaderWrapper wrapper : rtspLoaderWrappers) {
      if (!wrapper.canceled) {
        active = true;
        if (wrapper.loadInfo.loadable.hasReceivedRtpPacket()) return;
      }
    }
    if (active) retryWithRtpTcp();
  }

  private void retryWithRtpTcp() {
    handler.removeCallbacks(udpProbeTimeout);''')
    s = replace(s, '      // Validate that the trackTimingList contains timings for the selected tracks, and notify the',
        '''      // Probe only startup: retain the configured packet timeout once UDP is receiving data.
      if (!isUsingRtpTcp) {
        handler.removeCallbacks(udpProbeTimeout);
        handler.postDelayed(udpProbeTimeout, udpProbeTimeoutMs);
      }

      // Validate that the trackTimingList contains timings for the selected tracks, and notify the''')
    s = replace(s, '  @Nullable private IOException preparationError;', '''  private final List<int[]> trackBindings = new ArrayList<>(); // loader index, TS track id
  private final List<Integer> selectedTrackGroups = new ArrayList<>();
  private boolean transportSetupRequested;
  @Nullable private IOException preparationError;''')
    s = replace(s, '    selectedLoadInfos.clear();\n    for (int i = 0; i < selections.length; i++) {',
        '    selectedLoadInfos.clear();\n    selectedTrackGroups.clear();\n    for (int i = 0; i < selections.length; i++) {')
    s = replace(s, '      selectedLoadInfos.add(checkNotNull(rtspLoaderWrappers.get(trackGroupIndex)).loadInfo);', '''      RtpLoadInfo info = rtspLoaderWrappers.get(trackBindings.get(trackGroupIndex)[0]).loadInfo;
      if (!selectedLoadInfos.contains(info)) selectedLoadInfos.add(info);
      selectedTrackGroups.add(trackGroupIndex);''')
    s = replace(s, '          streams[i] = new SampleStreamImpl(trackGroupIndex);', '''          if (transportSetupRequested && positionUs != 0) {
            @Nullable SampleQueue queue = queueForTrack(trackGroupIndex);
            if (queue != null) queue.seekTo(positionUs, /* allowTimeBeyondBuffer= */ true);
          }
          streams[i] = new SampleStreamImpl(trackGroupIndex);''')
    s = replace(s, '    if (positionUs != 0) {', '    if (positionUs != 0 && !transportSetupRequested) {')
    s = replace(s, '        loaderWrapper.sampleQueue.discardTo(positionUs, toKeyframe, /* stopAtReadPosition= */ true);',
        '''        for (int id : loaderWrapper.ids()) {
          // Unselected tracks share the transport but have no reader to advance them.
          checkNotNull(loaderWrapper.queue(id)).discardTo(positionUs, toKeyframe, isTrackSelected(i, id));
        }''')
    s = replace(s, '    return !suppressRead() && rtspLoaderWrappers.get(trackGroupIndex).isSampleQueueReady();', '''    SampleQueue queue = queueForTrack(trackGroupIndex);
    return !suppressRead() && queue != null && queue.isReady(loaderForTrack(trackGroupIndex).canceled);''')
    s = replace(s, '    return rtspLoaderWrappers.get(sampleQueueIndex).read(formatHolder, buffer, readFlags);', '''    SampleQueue queue = queueForTrack(sampleQueueIndex);
    return queue == null ? C.RESULT_NOTHING_READ
        : queue.read(formatHolder, buffer, readFlags, loaderForTrack(sampleQueueIndex).canceled);''')
    s = replace(s, '    return rtspLoaderWrappers.get(sampleQueueIndex).skipData(positionUs);', '''    SampleQueue queue = queueForTrack(sampleQueueIndex);
    if (queue == null) return 0;
    int count = queue.getSkipCount(positionUs, loaderForTrack(sampleQueueIndex).canceled);
    queue.skip(count);
    return count;''')
    s = replace(s, '  private boolean suppressRead() {', '''  private RtspLoaderWrapper loaderForTrack(int index) {
    return rtspLoaderWrappers.get(trackBindings.get(index)[0]);
  }

  @Nullable private SampleQueue queueForTrack(int index) {
    return loaderForTrack(index).queue(trackBindings.get(index)[1]);
  }

  private boolean isTrackSelected(int loaderIndex, int trackId) {
    for (int index : selectedTrackGroups) {
      int[] binding = trackBindings.get(index);
      if (binding[0] == loaderIndex && binding[1] == trackId) return true;
    }
    return false;
  }

  private boolean suppressRead() {''')
    s = replace(s, '    if (transportReady && trackSelected) {',
        '    if (transportReady && trackSelected && !selectedLoadInfos.isEmpty() && !transportSetupRequested) {\n      transportSetupRequested = true;')
    s = replace(s, '    isUsingRtpTcp = true;\n\n    rtspClient.retryWithRtpTcp();',
        '    isUsingRtpTcp = true;\n    transportSetupRequested = false;\n\n    rtspClient.retryWithRtpTcp();')
    s = replace(s, '''      if (rtspLoaderWrappers.get(i).sampleQueue.getUpstreamFormat() == null) {
        return;
      }''', '''      RtspLoaderWrapper wrapper = rtspLoaderWrappers.get(i);
      if (!wrapper.tracksEnded || wrapper.queues().isEmpty()) return;
      for (SampleQueue queue : wrapper.queues()) {
        if (queue.getUpstreamFormat() == null) return;
      }''')
    s = replace(s, '''    trackGroups = buildTrackGroups(ImmutableList.copyOf(rtspLoaderWrappers));''', '''    ImmutableList.Builder<TrackGroup> groups = ImmutableList.builder();
    trackBindings.clear();
    for (int i = 0; i < rtspLoaderWrappers.size(); i++) {
      RtspLoaderWrapper wrapper = rtspLoaderWrappers.get(i);
      for (int id : wrapper.ids()) {
        trackBindings.add(new int[] {i, id});
        groups.add(new TrackGroup(Integer.toString(trackBindings.size() - 1),
            checkNotNull(checkNotNull(wrapper.queue(id)).getUpstreamFormat())));
      }
    }
    trackGroups = groups.build();''')
    s = replace(s, '''      SampleQueue sampleQueue = rtspLoaderWrappers.get(i).sampleQueue;
      if (!sampleQueue.seekTo(positionUs, /* allowTimeBeyondBuffer= */ loadingFinished)) {
        return false;
      }''', '''      RtspLoaderWrapper wrapper = rtspLoaderWrappers.get(i);
      for (int id : wrapper.ids()) {
        if (isTrackSelected(i, id) && !checkNotNull(wrapper.queue(id)).seekTo(positionUs, loadingFinished)) return false;
      }''')
    start = s.index('  private static ImmutableList<TrackGroup> buildTrackGroups(')
    end = s.index('  private final class InternalListener', start)
    s = s[:start] + '''  // All interactions that add queues are on the loading thread.
  private final class ExtractorOutputImpl implements ExtractorOutput {
    private final RtspLoaderWrapper wrapper;
    ExtractorOutputImpl(RtspLoaderWrapper wrapper) { this.wrapper = wrapper; }
    @Override public TrackOutput track(int id, int type) { return wrapper.addQueue(id); }
    @Override public void endTracks() {
      handler.post(() -> { wrapper.tracksEnded = true; maybeFinishPrepare(); });
    }
    @Override public void seekMap(SeekMap seekMap) {}
  }

''' + s[end:]
    s = replace(s, '      listener.onSourceInfoRefreshed(timing);', '''      // TS formats only become known after PAT/PMT arrive. Bootstrap SETUP/PLAY
      // before ExoPlayer can select the extracted audio/video tracks.
      boolean needsTsDiscovery = false;
      for (RtspLoaderWrapper wrapper : rtspLoaderWrappers) {
        needsTsDiscovery |= "MP2T".equalsIgnoreCase(wrapper.loadInfo.mediaTrack.payloadFormat.mediaEncoding);
      }
      if (needsTsDiscovery) {
        for (RtspLoaderWrapper wrapper : rtspLoaderWrappers) selectedLoadInfos.add(wrapper.loadInfo);
        trackSelected = true;
      }
      maybeSetupTracks();
      listener.onSourceInfoRefreshed(timing);''')
    s = replace(s, '    private final SampleQueue sampleQueue;', '''    private final android.util.SparseArray<SampleQueue> sampleQueues = new android.util.SparseArray<>();
    private boolean tracksEnded;''')
    s = replace(s, '''      sampleQueue = SampleQueue.createWithoutDrm(allocator);
      loadInfo = new RtpLoadInfo(mediaTrack, trackId, sampleQueue, rtpDataChannelFactory);
      sampleQueue.setUpstreamFormatChangeListener(internalListener);''', '''      loadInfo = new RtpLoadInfo(mediaTrack, trackId, new ExtractorOutputImpl(this), rtpDataChannelFactory);''')
    start = s.index('    public long getBufferedPositionUs() {', s.index('  private final class RtspLoaderWrapper'))
    end = s.index('    /** Starts loading. */', start)
    s = s[:start] + '''    synchronized TrackOutput addQueue(int id) {
      if (released) return new androidx.media3.extractor.DiscardingTrackOutput();
      SampleQueue queue = sampleQueues.get(id);
      if (queue == null) {
        queue = SampleQueue.createWithoutDrm(allocator);
        queue.setUpstreamFormatChangeListener(internalListener);
        sampleQueues.put(id, queue);
      }
      return queue;
    }
    @Nullable synchronized SampleQueue queue(int id) { return sampleQueues.get(id); }
    synchronized List<SampleQueue> queues() {
      ArrayList<SampleQueue> result = new ArrayList<>();
      for (int i = 0; i < sampleQueues.size(); i++) result.add(sampleQueues.valueAt(i));
      return result;
    }
    synchronized List<Integer> ids() {
      ArrayList<Integer> result = new ArrayList<>();
      for (int i = 0; i < sampleQueues.size(); i++) result.add(sampleQueues.keyAt(i));
      return result;
    }
    public long getBufferedPositionUs() {
      long end = Long.MAX_VALUE;
      int loaderIndex = rtspLoaderWrappers.indexOf(this);
      for (int id : ids()) {
        if (prepared && !isTrackSelected(loaderIndex, id)) continue;
        SampleQueue queue = checkNotNull(queue(id));
        long value = queue.getLargestQueuedTimestampUs();
        if (value != Long.MIN_VALUE) end = Math.min(end, value);
      }
      return end == Long.MAX_VALUE ? Long.MIN_VALUE : end;
    }

''' + s[end:]
    start = s.index('    public boolean isSampleQueueReady() {')
    end = s.index('    /** Cancels loading. */', start)
    s = s[:start] + s[end:]
    s = replace(s, '''        sampleQueue.reset();
        sampleQueue.setStartTimeUs(positionUs);''', '''        for (SampleQueue queue : queues()) {
          queue.reset();
          queue.setStartTimeUs(positionUs);
        }''')
    s = replace(s, '''      loader.release();
      sampleQueue.release();
      released = true;''', '''      synchronized (this) { released = true; }
      // A canceled loader can still be writing. Release queues after its thread exits.
      loader.release(() -> { for (SampleQueue queue : queues()) queue.release(); });''')
    s = replace(s, '      oldRtspLoaderWrappers.get(i).cancelLoad();', '''      RtspLoaderWrapper old = oldRtspLoaderWrappers.get(i);
      if (old != rtspLoaderWrappers.get(i)) {
        old.cancelLoad();
        old.release();
      }''')
    s = replace(s, '        TrackOutput trackOutput,', '        ExtractorOutput extractorOutput,')
    s = replace(s, '/* output= */ new ExtractorOutputImpl(trackOutput),\n              rtpDataChannelFactory);',
        '/* output= */ extractorOutput,\n              rtpDataChannelFactory, tsFactory);')
    out['RtspMediaPeriod'] = s
    return {PACKAGE + key: value for key, value in out.items()}


def archive(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 9, 10, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('aar', 'sources', 'classpath', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--javac', required=True)
    args = parser.parse_args()
    for file, digest in ((args.aar, AAR_SHA256), (args.sources, SOURCES_SHA256)):
        if hashlib.sha256(file.read_bytes()).hexdigest() != digest:
            raise ValueError('Unexpected official Media3 1.11.1 input: ' + file.name)
    with zipfile.ZipFile(args.aar) as z:
        entries = {n: z.read(n) for n in z.namelist()}
    with zipfile.ZipFile(io.BytesIO(entries['classes.jar'])) as z:
        classes = {n: z.read(n) for n in z.namelist()}
    with zipfile.ZipFile(args.sources) as z:
        java = patches(z)
    java[PACKAGE + 'reader/RtpMp2tReader'] = (Path(__file__).parent / 'rtsp/RtpMp2tReader.java').read_text(encoding='utf-8')
    with tempfile.TemporaryDirectory(prefix='mytv-rtsp-') as directory:
        work = Path(directory)
        files = []
        for name, source in java.items():
            path = work / (name + '.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding='utf-8')
            files.append(str(path))
        upstream = work / 'upstream.jar'
        upstream.write_bytes(entries['classes.jar'])
        output = work / 'classes'
        output.mkdir()
        classpath = str(upstream) + os.pathsep + args.classpath.read_text(encoding='utf-8-sig').strip()
        arguments = ['-proc:none', '-encoding', 'UTF-8', '--release', '8', '-classpath', classpath, '-d', str(output)] + files
        argfile = work / 'javac.args'
        argfile.write_text('\n'.join('"' + arg.replace('\\', '/').replace('"', '\\"') + '"' for arg in arguments), encoding='utf-8')
        subprocess.run([args.javac, '-J-Dfile.encoding=UTF-8', '@' + str(argfile)], check=True)
        for name in java:
            classes = {n: b for n, b in classes.items() if n != name + '.class' and not n.startswith(name + '$')}
        for path in output.rglob('*.class'):
            classes[path.relative_to(output).as_posix()] = path.read_bytes()
        entries['classes.jar'] = archive(classes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(archive(entries))
    print('Built', args.output.name, hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
