// ==UserScript==
// @name         BSS Nitro Full Capture
// @namespace    gearth.bss.capture
// @version      2.0
// @description  Capture EVERYTHING the bsshotel/Nitro client exposes: WebSocket frames (decoded EVA-WIRE), every fetch/XHR request+response (headers+body), and the environment (cookies, UA, origin, socket url). Runs in the browser where the connection already works.
// @match        *://*.bsshotel.it/*
// @match        *://bsshotel.it/*
// @match        *://gh.b55.live/*
// @run-at       document-start
// @grant        GM_xmlhttpRequest
// @connect      gh.b55.live
// @connect      *
// ==/UserScript==

(function () {
    'use strict';

    // ---------------------------------------------------------------- store
    const cap = {
        env: {},
        ws: [],    // websocket lifecycle + frames
        http: [],  // fetch + XHR
    };
    window.__bssCapture = cap;

    // ---------------------------------------------------------------- helpers
    const now = () => new Date().toISOString();

    const hex = (bytes) => {
        let s = '';
        for (let i = 0; i < bytes.length; i++) s += bytes[i].toString(16).padStart(2, '0');
        return s;
    };

    const toBytes = (data) =>
        new Promise((resolve) => {
            if (data instanceof ArrayBuffer) resolve(new Uint8Array(data));
            else if (ArrayBuffer.isView(data)) resolve(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
            else if (data instanceof Blob) data.arrayBuffer().then((b) => resolve(new Uint8Array(b)));
            else resolve(null); // string / other
        });

    // EVA-WIRE: [int32 length][int16 header][payload...]; length counts header+payload.
    const decodeHeader = (bytes) => {
        if (!bytes || bytes.length < 6) return { id: null, length: bytes ? bytes.length : 0 };
        const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        return { length: dv.getInt32(0), id: dv.getUint16(4) };
    };

    const headersToObj = (h) => {
        const o = {};
        try {
            if (!h) return o;
            if (h instanceof Headers) { h.forEach((v, k) => (o[k] = v)); return o; }
            if (Array.isArray(h)) { h.forEach(([k, v]) => (o[k] = v)); return o; }
            if (typeof h === 'string') {
                h.trim().split(/\r?\n/).forEach((line) => {
                    const i = line.indexOf(':');
                    if (i > 0) o[line.slice(0, i).trim()] = line.slice(i + 1).trim();
                });
                return o;
            }
            Object.assign(o, h);
        } catch (e) {}
        return o;
    };

    // ---------------------------------------------------------------- environment
    function snapshotEnv() {
        cap.env = {
            time: now(),
            url: location.href,
            origin: location.origin,
            userAgent: navigator.userAgent,
            language: navigator.language,
            cookies: document.cookie,
            // Nitro config values, if the client exposed them globally.
            nitroConfig: (function () {
                try { return typeof NitroConfig !== 'undefined' ? NitroConfig : null; } catch (e) { return null; }
            })(),
        };
    }
    snapshotEnv();
    document.addEventListener('DOMContentLoaded', snapshotEnv);

    // ---------------------------------------------------------------- WebSocket hook
    const NativeWebSocket = window.WebSocket;
    let wsCounter = 0;
    let frameCounter = 0;

    function HookedWebSocket(url, protocols) {
        const ws = protocols !== undefined ? new NativeWebSocket(url, protocols) : new NativeWebSocket(url);

        const rec = {
            n: ++wsCounter,
            openedAt: now(),
            url,
            protocols: protocols || null,
            binaryType: ws.binaryType,
            frames: [],
            closedAt: null,
            closeCode: null,
        };
        cap.ws.push(rec);
        console.log('%c[BSS ws#' + rec.n + '] open -> ' + url, 'color:#3498db;font-weight:bold');

        const pushFrame = (dir, bytes) => {
            if (!bytes) return;
            const { id, length } = decodeHeader(bytes);
            const f = { n: ++frameCounter, t: now(), dir, id, len: bytes.length, evaLen: length, hex: hex(bytes) };
            rec.frames.push(f);
            console.log(
                '%c[BSS ' + dir + '] #' + f.n + ' id=' + id + ' (' + bytes.length + 'B)',
                dir === 'C->S' ? 'color:#e67e22' : 'color:#2ecc71',
                f.hex
            );
        };

        const origSend = ws.send.bind(ws);
        ws.send = function (data) {
            toBytes(data).then((b) => pushFrame('C->S', b));
            return origSend(data);
        };
        ws.addEventListener('message', (ev) => toBytes(ev.data).then((b) => pushFrame('S->C', b)));
        ws.addEventListener('close', (ev) => {
            rec.closedAt = now();
            rec.closeCode = ev.code;
            console.log('%c[BSS ws#' + rec.n + '] closed (' + ev.code + ')', 'color:#e74c3c;font-weight:bold');
        });

        return ws;
    }
    HookedWebSocket.prototype = NativeWebSocket.prototype;
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach((k) => (HookedWebSocket[k] = NativeWebSocket[k]));
    window.WebSocket = HookedWebSocket;

    // ---------------------------------------------------------------- fetch hook
    const nativeFetch = window.fetch;
    if (nativeFetch) {
        window.fetch = function (input, init) {
            const req = {
                kind: 'fetch',
                t: now(),
                method: (init && init.method) || (input && input.method) || 'GET',
                url: typeof input === 'string' ? input : (input && input.url) || String(input),
                reqHeaders: headersToObj((init && init.headers) || (input && input.headers)),
                reqBody: (init && typeof init.body === 'string') ? init.body : (init && init.body ? '[binary]' : null),
                status: null,
                resHeaders: null,
                resBody: null,
            };
            cap.http.push(req);
            return nativeFetch.apply(this, arguments).then((res) => {
                req.status = res.status;
                req.resHeaders = headersToObj(res.headers);
                try {
                    const ct = res.headers.get('content-type') || '';
                    if (/json|text|javascript|xml/i.test(ct)) {
                        res.clone().text().then((t) => (req.resBody = t.length > 20000 ? t.slice(0, 20000) + '…[truncated]' : t)).catch(() => {});
                    } else {
                        req.resBody = '[binary ' + (res.headers.get('content-length') || '?') + 'B]';
                    }
                } catch (e) {}
                return res;
            });
        };
    }

    // ---------------------------------------------------------------- XHR hook
    const XHR = window.XMLHttpRequest;
    if (XHR) {
        const open = XHR.prototype.open;
        const send = XHR.prototype.send;
        const setH = XHR.prototype.setRequestHeader;
        XHR.prototype.open = function (method, url) {
            this.__cap = { kind: 'xhr', t: now(), method, url, reqHeaders: {}, reqBody: null, status: null, resHeaders: null, resBody: null };
            return open.apply(this, arguments);
        };
        XHR.prototype.setRequestHeader = function (k, v) {
            if (this.__cap) this.__cap.reqHeaders[k] = v;
            return setH.apply(this, arguments);
        };
        XHR.prototype.send = function (body) {
            if (this.__cap) {
                this.__cap.reqBody = typeof body === 'string' ? body : body ? '[binary]' : null;
                cap.http.push(this.__cap);
                this.addEventListener('load', () => {
                    try {
                        this.__cap.status = this.status;
                        this.__cap.resHeaders = headersToObj(this.getAllResponseHeaders());
                        const ct = (this.getResponseHeader('content-type') || '');
                        if (/json|text|javascript|xml/i.test(ct) && typeof this.responseText === 'string') {
                            this.__cap.resBody = this.responseText.length > 20000 ? this.responseText.slice(0, 20000) + '…[truncated]' : this.responseText;
                        } else {
                            this.__cap.resBody = '[binary]';
                        }
                    } catch (e) {}
                });
            }
            return send.apply(this, arguments);
        };
    }

    // ---------------------------------------------------------------- handshake probe (GM_xmlhttpRequest power)
    // Replays the WebSocket upgrade to gh.b55.live with different header sets and reports the raw
    // status + response headers. GM_xmlhttpRequest can send the forbidden Connection/Upgrade/Sec-*
    // headers that plain fetch/XHR cannot, and uses the browser's own TLS stack.
    cap.probes = [];
    window.__bssProbes = cap.probes;

    const randKey = () => {
        const a = new Uint8Array(16);
        crypto.getRandomValues(a);
        return btoa(String.fromCharCode.apply(null, a));
    };

    const WS_HOST = 'gh.b55.live';
    const WS_URL = 'https://' + WS_HOST + '/';

    // Each variant: a label + the request headers to send.
    const variants = () => [
        {
            label: 'A minimal (browser-like)',
            headers: {
                'Connection': 'Upgrade',
                'Upgrade': 'websocket',
                'Sec-WebSocket-Version': '13',
                'Sec-WebSocket-Key': randKey(),
                'Origin': 'https://bsshotel.it',
            },
        },
        {
            label: 'B minimal + clean extensions',
            headers: {
                'Connection': 'Upgrade',
                'Upgrade': 'websocket',
                'Sec-WebSocket-Version': '13',
                'Sec-WebSocket-Key': randKey(),
                'Origin': 'https://bsshotel.it',
                'Sec-WebSocket-Extensions': 'permessage-deflate; client_max_window_bits',
            },
        },
        {
            label: 'C G-Earth exact (lowercase-token via value)',
            headers: {
                'Pragma': 'no-cache',
                'Cache-Control': 'no-cache',
                'User-Agent': navigator.userAgent,
                'Origin': 'https://bsshotel.it',
                'Accept-Encoding': 'gzip, deflate, br, zstd',
                'Accept-Language': navigator.language,
                'Upgrade': 'websocket',
                'Connection': 'Upgrade',
                'Sec-WebSocket-Key': randKey(),
                'Sec-WebSocket-Version': '13',
                'Sec-WebSocket-Extensions': 'permessage-deflate;client_max_window_bits',
            },
        },
        {
            label: 'D no Origin (control, expect reject)',
            headers: {
                'Connection': 'Upgrade',
                'Upgrade': 'websocket',
                'Sec-WebSocket-Version': '13',
                'Sec-WebSocket-Key': randKey(),
            },
        },
    ];

    const runProbe = (v) =>
        new Promise((resolve) => {
            const started = Date.now();
            GM_xmlhttpRequest({
                method: 'GET',
                url: WS_URL,
                headers: v.headers,
                timeout: 8000,
                anonymous: false,
                onload: (r) => resolve({ label: v.label, sent: v.headers, status: r.status, statusText: r.statusText, respHeaders: r.responseHeaders, ms: Date.now() - started }),
                onerror: (r) => resolve({ label: v.label, sent: v.headers, status: 'ERROR', detail: r && (r.error || r.statusText), respHeaders: r && r.responseHeaders, ms: Date.now() - started }),
                ontimeout: () => resolve({ label: v.label, sent: v.headers, status: 'TIMEOUT (likely upgraded/accepted — server held the socket open)', ms: Date.now() - started }),
            });
        });

    window.probeBssHandshake = async function () {
        console.log('%c[BSS probe] replaying handshakes to ' + WS_URL + ' …', 'color:#f39c12;font-weight:bold');
        cap.probes.length = 0;
        for (const v of variants()) {
            const res = await runProbe(v);
            cap.probes.push(res);
            const ok = /101/.test(String(res.status)) || /TIMEOUT/.test(String(res.status));
            console.log(
                '%c[BSS probe] ' + res.label + ' -> ' + res.status + ' (' + res.ms + 'ms)',
                'color:' + (ok ? '#2ecc71' : '#e74c3c') + ';font-weight:bold'
            );
            if (res.respHeaders) console.log(res.respHeaders.trim());
        }
        console.log('%c[BSS probe] done. Inspect window.__bssProbes; a 400 = server rejected that header set, 101/TIMEOUT = accepted.', 'color:#f39c12;font-weight:bold');
        return cap.probes;
    };

    // ---------------------------------------------------------------- exports
    // dumpBssCapture()      -> download full capture as JSON
    // dumpBssFrames()       -> download just the WS frames as TSV (id + hex)
    // window.__bssCapture   -> live object to inspect in the console
    window.dumpBssCapture = function () {
        snapshotEnv();
        const blob = new Blob([JSON.stringify(cap, null, 2)], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'bss-capture.json';
        a.click();
        console.log('[BSS] dumped full capture (' + cap.ws.reduce((s, w) => s + w.frames.length, 0) + ' frames, ' + cap.http.length + ' http)');
    };

    window.dumpBssFrames = function () {
        const rows = [];
        cap.ws.forEach((w) =>
            w.frames.forEach((f) => rows.push([f.n, f.t, w.n, f.dir, 'id=' + f.id, 'len=' + f.len, f.hex].join('\t')))
        );
        const blob = new Blob(['# n\ttime\tws\tdir\tid\tlen\thex\n' + rows.join('\n')], { type: 'text/plain' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'bss-ws-frames.txt';
        a.click();
        console.log('[BSS] dumped ' + rows.length + ' frames');
    };

    // ---------------------------------------------------------------- floating save button + auto-save
    function addButton() {
        if (document.getElementById('bss-cap-btn')) return;
        const btn = document.createElement('button');
        btn.id = 'bss-cap-btn';
        btn.textContent = '💾 Save BSS capture';
        btn.style.cssText =
            'position:fixed;bottom:12px;right:12px;z-index:2147483647;padding:8px 12px;' +
            'font:600 12px/1.2 sans-serif;color:#fff;background:#9b59b6;border:none;border-radius:6px;' +
            'box-shadow:0 2px 8px rgba(0,0,0,.4);cursor:pointer;opacity:.85;';
        btn.onmouseenter = () => (btn.style.opacity = '1');
        btn.onmouseleave = () => (btn.style.opacity = '.85');
        btn.onclick = () => window.dumpBssCapture();
        const count = document.createElement('span');
        count.style.cssText = 'margin-left:6px;font-weight:400;opacity:.9;';
        setInterval(() => {
            const f = cap.ws.reduce((s, w) => s + w.frames.length, 0);
            count.textContent = '(' + f + ' frames)';
        }, 1000);
        btn.appendChild(count);
        document.body.appendChild(btn);

        const probe = document.createElement('button');
        probe.id = 'bss-probe-btn';
        probe.textContent = '🔬 Probe handshake';
        probe.style.cssText =
            'position:fixed;bottom:48px;right:12px;z-index:2147483647;padding:8px 12px;' +
            'font:600 12px/1.2 sans-serif;color:#fff;background:#f39c12;border:none;border-radius:6px;' +
            'box-shadow:0 2px 8px rgba(0,0,0,.4);cursor:pointer;opacity:.85;';
        probe.onmouseenter = () => (probe.style.opacity = '1');
        probe.onmouseleave = () => (probe.style.opacity = '.85');
        probe.onclick = () => window.probeBssHandshake();
        document.body.appendChild(probe);
    }
    if (document.body) addButton();
    else document.addEventListener('DOMContentLoaded', addButton);

    // Best-effort auto-save when leaving the page so a capture is never lost.
    window.addEventListener('beforeunload', () => {
        try { if (cap.ws.some((w) => w.frames.length)) window.dumpBssCapture(); } catch (e) {}
    });

    console.log('%c[BSS] Full capture installed.\n  💾 button (bottom-right) or dumpBssCapture() – download everything as JSON\n  dumpBssFrames()  – download WS frames as TSV\n  window.__bssCapture – live object (env / ws / http)', 'color:#9b59b6;font-weight:bold');
})();
