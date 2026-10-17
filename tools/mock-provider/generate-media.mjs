#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.dirname(fileURLToPath(import.meta.url));
const mediaDir = path.join(dir, 'media');
fs.mkdirSync(mediaDir, { recursive: true });
const srt = path.join(mediaDir, 'subtitles.srt');
fs.writeFileSync(srt, '1\n00:00:02,000 --> 00:00:08,000\nSynthetic media demo\n\n2\n00:00:12,000 --> 00:00:18,000\n\n', 'utf8');

function generate(output, format, video = true) {
  const args = ['-hide_banner', '-loglevel', 'error', '-y'];
  if (video) args.push('-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24:duration=120');
  args.push('-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=120');
  args.push('-f', 'lavfi', '-i', 'sine=frequency=880:sample_rate=48000:duration=120');
  args.push('-i', srt, '-t', '120');
  if (video) args.push('-map', '0:v:0');
  args.push('-map', '1:a:0', '-map', '2:a:0');
  if (format !== 'mpegts') args.push('-map', '3:s:0');
  if (video) args.push('-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '30', '-pix_fmt', 'yuv420p');
  args.push('-c:a', 'aac', '-b:a', '96k', '-metadata:s:a:0', 'language=eng', '-metadata:s:a:0', 'title=English 440 Hz', '-metadata:s:a:1', 'language=ara', '-metadata:s:a:1', 'title=Arabic 880 Hz');
  if (format === 'mpegts') args.push('-f', 'mpegts');
  else args.push('-c:s', 'mov_text', '-movflags', '+faststart');
  args.push(path.join(mediaDir, output));
  const result = spawnSync('ffmpeg', args, { stdio: 'inherit', windowsHide: true });
  if (result.error) throw new Error(`Could not launch ffmpeg: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`ffmpeg exited with status ${result.status}`);
}

try {
  generate('movie.mp4', 'mp4');
  generate('episode-A1.mp4', 'mp4');
  generate('episode-B1.mp4', 'mp4');
  generate('live.ts', 'mpegts');
  console.log(`Generated four 120-second test clips under ${mediaDir}`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
