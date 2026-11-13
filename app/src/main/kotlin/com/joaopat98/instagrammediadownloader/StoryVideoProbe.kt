package com.joaopat98.instagrammediadownloader

import org.json.JSONObject

internal fun storyVideoProbe(storyId: String, storyPath: String) = """
    (() => {
      if (window !== window.top ||
          location.pathname.replace(/\/$/, '') !== ${JSONObject.quote(storyPath)}) return;
      const id = ${JSONObject.quote(storyId)};
      let candidate = null;
      let bestArea = -1;
      let responses = 0;
      let matches = 0;

      const isStoryData = raw => {
        try {
          const url = new URL(raw, location.href);
          return url.origin === location.origin && /\/(?:graphql|api)(?:\/|$)/.test(url.pathname);
        } catch (_) { return false; }
      };
      const add = entry => {
        try {
          const url = new URL(entry.url);
          if (url.protocol !== 'https:' ||
              !/\.(?:cdninstagram\.com|fbcdn\.net)$/.test(url.hostname) ||
              !url.pathname.toLowerCase().endsWith('.mp4') ||
              ['bytestart', 'byteend', 'range'].some(key => url.searchParams.has(key))) return;
          const area = (Number(entry.width) || 0) * (Number(entry.height) || 0);
          if (area > bestArea) {
            candidate = url.href;
            bestArea = area;
          }
        } catch (_) {}
      };
      const scan = (value, depth = 0) => {
        if (!value || typeof value !== 'object' || depth > 70) return;
        if ([value.pk, value.id, value.media_id, value.story_id].some(
            key => typeof key === 'string' && key.split('_')[0] === id)) {
          matches++;
          if (Array.isArray(value.video_versions)) value.video_versions.forEach(add);
          [value.video_url, value.progressive_url].filter(Boolean).forEach(url =>
            add({url, width: value.original_width, height: value.original_height}));
        }
        for (const child of Object.values(value)) scan(child, depth + 1);
      };
      const inspect = raw => {
        if (!raw || raw.length > 8 * 1024 * 1024) return;
        responses++;
        const text = raw.replace(/^for\s*\(;;\);/, '').trim()
          .replace(/("(?:pk|id|media_id|story_id)"\s*:\s*)(\d{16,})(?=\s*[,}])/g, '$1"$2"');
        try { scan(JSON.parse(text)); }
        catch (_) {
          for (const line of text.split('\n')) {
            try { scan(JSON.parse(line)); } catch (_) {}
          }
        }
      };
      const fetch = window.fetch;
      window.fetch = function(...args) {
        const pending = fetch.apply(this, args);
        const url = args[0] instanceof Request ? args[0].url : args[0];
        if (isStoryData(url)) {
          pending.then(response => {
            if (response.ok && isStoryData(response.url)) response.clone().text().then(inspect).catch(() => {});
          }).catch(() => {});
        }
        return pending;
      };
      const open = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(method, url, ...args) {
        const result = open.call(this, method, url, ...args);
        if (isStoryData(url)) this.addEventListener('load', () => {
          if (!isStoryData(this.responseURL)) return;
          if (this.responseType === '' || this.responseType === 'text') inspect(this.responseText);
          else if (this.responseType === 'json') { responses++; scan(this.response); }
        }, {once: true});
        return result;
      };
      window.souvenirVideoProbe = () => ({candidate, responses, matches});
    })()
""".trimIndent()
