#!/usr/bin/env python3
"""Rebuild the 64-bit Media3 1.4.1 FFmpeg/AV3A JNI libraries with NDK r27d."""
import argparse, concurrent.futures, pathlib, re, subprocess, zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source-dir', type=pathlib.Path, required=True, help='Sourcecodeforplayer e7d244d29454eb04c968cd98a30587303a9c15f8')
parser.add_argument('--ndk', type=pathlib.Path, required=True, help='Android NDK r27d root')
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--aar', type=pathlib.Path, help='Replace only the two 64-bit JNI entries in this AAR')
args = parser.parse_args()
SRC = args.source_dir.resolve()
BIN = args.ndk.resolve() / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
ROOT = args.output.resolve()
FF = SRC / 'ffmpeg/ffmpeg-6.1'
RENDER = SRC / 'av3a_binaural_render/AudioDecoder/av3a_binaural_render'
JNI = pathlib.Path(__file__).with_name('ffmpeg_jni.cc')
for script in (FF / 'ffbuild').glob('*.sh'):
    script.chmod(script.stat().st_mode | 0o111)

def run(args, cwd, log):
    subprocess.run([str(a) for a in args], cwd=cwd, stdout=log, stderr=subprocess.STDOUT, check=True)

# The application embeds both AV3A dependencies in libffmpegJNI, as its old AAR did.
p = FF / 'libavcodec/libarcdav3a.c'
original_file = p.with_suffix('.c.original')
if not original_file.exists():
    original_file.write_bytes(p.read_bytes())
original = original_file.read_text()
header = (FF / 'libavcodec/arcdav3a.h').read_text()
decls = re.sub(r'typedef (.+?)\(\*PF(\w+)\)(\([^;]+;)', r'extern \1\2\3', '\n'.join(l for l in header.splitlines() if l.startswith('typedef ')))
s = original.replace('#include "arcdav3a.h"', '#include "arcdav3a.h"\n' + decls)
s = re.sub(r'\s*h->renderhandle = dlopen.*?(?=\s*h->CreateRenderer\s*=)', '\n', s, flags=re.S)
s = re.sub(r'\s*if\(!h->handle\).*?(?=\s*h->avs3_create_decoder\s*=)', '\n', s, flags=re.S)
s = re.sub(r'\(PF\w+\)dlsym\(h->\w+, "(\w+)"\)', r'\1', s)
p.write_text(s)

makefile = (RENDER / 'build/linux/av3a_binaural_render_android.mk').read_text()
renderer_sources = sorted(set(re.findall(r': (../../\S+\.(?:cpp|cc|c))\s', makefile)))
dec_sources = [p for folder in ['avs3Decoder/src', 'libavs3_common', 'libavs3_debug']
               for p in (SRC / 'av3adecoder' / folder).glob('*.c')]

for abi, target, arch, cpu in [('arm64-v8a', 'aarch64-linux-android', 'aarch64', 'armv8-a'),
                               ('x86_64', 'x86_64-linux-android', 'x86_64', 'x86-64')]:
    out = ROOT / abi
    out.mkdir(parents=True, exist_ok=True)
    for generated in list(out.glob('lib*/*.version')) + list(out.glob('lib*/*.pc')):
        if generated.stat().st_size == 0:
            generated.unlink()
    cc, cxx = BIN / (target + '21-clang'), BIN / (target + '21-clang++')
    includes = [SRC / 'av3adecoder' / p for p in ['avs3Decoder/include', 'libavs3_common', 'libavs3_debug']]
    objects = []
    jobs = []
    for index, source in enumerate(dec_sources + [(RENDER / 'build/linux' / p).resolve() for p in renderer_sources]):
        obj = out / ('av3a-' + str(index) + '.o')
        objects.append(obj)
        flags = ['-O2', '-fPIC', '-fsigned-char', '-DANDROID', '-DHAVE_CONFIG_H', '-Wno-error=implicit-function-declaration', '-Wno-error=incompatible-pointer-types']
        flags += ['-I' + str(p) for p in includes + [RENDER, SRC / 'av3a_binaural_render/VMFFramework/bin/VMFSDK/include/PlatForm']]
        flags += ['-std=c++11'] if source.suffix in ('.cpp', '.cc') else ['-std=c99']
        if arch == 'aarch64': flags += ['-DSUPPORT_NEON']
        jobs.append(([cxx if source.suffix in ('.cpp', '.cc') else cc, *flags, '-c', source, '-o', obj], out / (obj.stem + '.log')))
    def compile_one(job):
        args, path = job
        with path.open('w') as log: run(args, out, log)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(compile_one, jobs))
    with (out / 'build.log').open('w') as log:
        run([BIN / 'llvm-ar', 'rcs', out / 'libav3a.a', *objects], out, log)
        opts = ['--target-os=android', '--enable-cross-compile', '--arch=' + arch, '--cpu=' + cpu,
                '--cc=' + str(cc), '--cxx=' + str(cxx), '--ar=' + str(BIN / 'llvm-ar'),
                '--nm=' + str(BIN / 'llvm-nm'), '--ranlib=' + str(BIN / 'llvm-ranlib'),
                '--strip=' + str(BIN / 'llvm-strip'), '--enable-pic', '--enable-static', '--disable-shared',
                '--disable-doc', '--disable-programs', '--disable-everything', '--disable-avdevice',
                '--disable-avformat', '--disable-swscale', '--disable-postproc', '--disable-avfilter',
                '--disable-symver', '--enable-swresample', '--disable-v4l2-m2m', '--disable-vulkan',
                '--disable-autodetect', '--enable-libarcdav3a',
                '--enable-decoder=vorbis,opus,flac,alac,pcm_mulaw,pcm_alaw,mp3,amrnb,amrwb,aac,ac3,eac3,dca,mlp,truehd,libarcdav3a,h264,hevc',
                '--extra-cflags=' + ' '.join('-I' + str(p) for p in includes)]
        if arch == 'x86_64': opts += ['--disable-x86asm']
        run(['bash', FF / 'configure', *opts], out, log)
        run(['make', '-j8'], out, log)
        run([cxx, '-std=c++11', '-O2', '-fPIC', '-shared', '-static-libstdc++',
             '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-Wl,-Bsymbolic',
             '-Wl,--no-undefined', '-Wl,-soname,libffmpegJNI.so', '-I' + str(FF), '-I' + str(out),
             JNI,
             '-Wl,--start-group', out / 'libswresample/libswresample.a', out / 'libavcodec/libavcodec.a',
             out / 'libavutil/libavutil.a', out / 'libav3a.a', '-Wl,--end-group',
             '-landroid', '-llog', '-lz', '-lm', '-ldl', '-o', out / 'libffmpegJNI.so'], out, log)
        run([BIN / 'llvm-strip', '--strip-unneeded', out / 'libffmpegJNI.so'], out, log)
    print(abi + ' READY', flush=True)

if args.aar:
    aar = args.aar.resolve()
    temporary = aar.with_suffix('.aar.tmp')
    replacements = {'jni/' + abi + '/libffmpegJNI.so': ROOT / abi / 'libffmpegJNI.so'
                    for abi in ('arm64-v8a', 'x86_64')}
    with zipfile.ZipFile(aar) as original, zipfile.ZipFile(temporary, 'w') as updated:
        assert replacements.keys() <= set(original.namelist()), 'Missing JNI entries in AAR'
        for entry in original.infolist():
            data = replacements[entry.filename].read_bytes() if entry.filename in replacements else original.read(entry)
            updated.writestr(entry, data)
    temporary.replace(aar)
    print('Updated ' + str(aar))
