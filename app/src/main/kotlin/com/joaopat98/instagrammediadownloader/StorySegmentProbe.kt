package com.joaopat98.instagrammediadownloader

import org.json.JSONObject

internal fun storySegmentProbe(path: String) = """
    (() => {
      if (window !== window.top || location.pathname.replace(/\/$/, '') !== ${JSONObject.quote(path)} ||
          !window.MediaSource || !window.SourceBuffer) return;
      const groups = new WeakMap();
      const buffers = new WeakMap();
      const sources = new Map();
      let bytes = 0;
      let overflow = false;
      let encrypted = false;
      let frozen = null;
      document.addEventListener('encrypted', () => { encrypted = true; }, true);

      const add = MediaSource.prototype.addSourceBuffer;
      MediaSource.prototype.addSourceBuffer = function(type) {
        const buffer = add.call(this, type);
        let group = groups.get(this);
        if (!group) { group = []; groups.set(this, group); }
        const track = {type, parts: [], length: 0};
        group.push(track);
        buffers.set(buffer, track);
        return buffer;
      };
      const create = URL.createObjectURL;
      URL.createObjectURL = function(object) {
        const url = create.call(this, object);
        if (object instanceof MediaSource) sources.set(url, object);
        return url;
      };
      const append = SourceBuffer.prototype.appendBuffer;
      SourceBuffer.prototype.appendBuffer = function(data) {
        const result = append.call(this, data);
        const track = buffers.get(this);
        if (track && !overflow && !encrypted) {
          const view = ArrayBuffer.isView(data)
            ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data);
          if (bytes + view.length > 64 * 1024 * 1024) overflow = true;
          else {
            track.parts.push(view.slice());
            track.length += view.length;
            bytes += view.length;
          }
        }
        return result;
      };
      window.souvenirFreezeStory = () => {
        if (location.pathname.replace(/\/$/, '') !== ${JSONObject.quote(path)}) return {state:'wrong-story'};
        if (encrypted) return {state:'encrypted'};
        if (overflow) return {state:'capture-limit'};
        const videos = Array.from(document.querySelectorAll('video')).filter(video => {
          const rect = video.getBoundingClientRect();
          return rect.width >= 160 && rect.height >= 160 && rect.bottom > 0 && rect.top < innerHeight;
        });
        if (videos.length !== 1) return {state:'waiting-for-video'};
        const video = videos[0];
        const source = sources.get(video.currentSrc || video.src);
        const tracks = source && groups.get(source);
        if (!tracks || !tracks.length) return {state:'no-captured-tracks'};
        const ranges = video.buffered;
        const duration = video.duration;
        if (!Number.isFinite(duration) || !ranges.length || ranges.start(0) > 0.35 ||
            ranges.end(ranges.length - 1) < duration - 0.4 ||
            Array.from({length:ranges.length - 1}, (_, i) => ranges.start(i + 1) - ranges.end(i))
              .some(gap => gap > 0.25)) return {state:'buffering', tracks:tracks.length};
        video.pause();
        frozen = tracks.map(track => ({type:track.type, parts:track.parts.slice(), length:track.length}));
        return {state:'ready', durationMs:Math.round(duration * 1000),
          tracks:frozen.map(track => ({type:track.type, size:track.length}))};
      };
      window.souvenirReadStoryBytes = (index, offset, limit) => {
        const track = frozen?.[index];
        if (!track || offset < 0 || limit > 131072 || offset >= track.length) return null;
        const chunks = [];
        let skip = offset;
        let remaining = limit;
        for (const part of track.parts) {
          if (skip >= part.length) { skip -= part.length; continue; }
          const end = Math.min(part.length, skip + remaining);
          for (let pos = skip; pos < end; pos += 8192) {
            chunks.push(String.fromCharCode(...part.subarray(pos, Math.min(pos + 8192, end))));
          }
          remaining -= end - skip;
          if (!remaining) break;
          skip = 0;
        }
        return btoa(chunks.join(''));
      };
    })()
""".trimIndent()
