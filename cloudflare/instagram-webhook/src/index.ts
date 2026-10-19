interface Env {
  INBOX_DB: D1Database;
  INSTAGRAM_APP_SECRET: string;
  INSTAGRAM_VERIFY_TOKEN: string;
  META_APP_SECRET?: string;
  INSTAGRAM_ACCESS_TOKEN?: string;
}

type MessagingEvent = {
  sender?: { id?: string };
  recipient?: { id?: string };
  message?: {
    mid?: string;
    text?: string;
    is_echo?: boolean;
    attachments?: Array<{
      type?: string;
      payload?: {
        reel_video_id?: string;
        title?: string;
        url?: string;
      };
    }>;
  };
};

const encoder = new TextEncoder();
const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PAIRING_TTL_MS = 10 * 60 * 1000;
const NOTE_TTL_MS = 10 * 60 * 1000;

function legalPage(title: string, content: string): Response {
  return new Response(
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title} · InstaPin</title><style>body{max-width:760px;margin:40px auto;padding:0 20px;font:16px/1.6 system-ui;color:#172033}h1,h2{line-height:1.2}a{color:#c70020}</style></head><body><h1>${title}</h1><p>Effective October 19, 2026</p>${content}<p>Contact: <a href="mailto:connect@aryanventures.co.in">connect@aryanventures.co.in</a></p></body></html>`,
    { headers: { "Content-Type": "text/html; charset=UTF-8" } }
  );
}

function jsonResponse(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store" } });
}

function hex(bytes: ArrayBuffer): string {
  return [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, "0")).join("");
}

function equal(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index++) {
    difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return difference === 0;
}

async function sha256(value: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

async function hasValidSignature(body: string, signature: string | null, secret: string): Promise<boolean> {
  if (!signature?.startsWith("sha256=") || !secret) return false;
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const digest = await crypto.subtle.sign("HMAC", key, encoder.encode(body));
  return equal(signature, `sha256=${hex(digest)}`);
}

function randomToken(byteCount = 32): string {
  const bytes = crypto.getRandomValues(new Uint8Array(byteCount));
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function randomPairingCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  const code = [...bytes].map(value => PAIRING_ALPHABET[value % PAIRING_ALPHABET.length]).join("");
  return `IP-${code.slice(0, 4)}-${code.slice(4)}`;
}

function bearerToken(request: Request): string | null {
  const authorization = request.headers.get("Authorization");
  return authorization?.startsWith("Bearer ") ? authorization.slice(7).trim() : null;
}

async function authorizedDevice(request: Request, env: Env): Promise<string | null> {
  const token = bearerToken(request);
  if (!token) return null;
  const row = await env.INBOX_DB.prepare(
    "SELECT id FROM devices WHERE token_hash = ? AND disconnected_at IS NULL"
  ).bind(await sha256(token)).first<{ id: string }>();
  return row?.id ?? null;
}

async function sendInstagramMessage(env: Env, accountId: string, recipientId: string, text: string): Promise<void> {
  if (!env.INSTAGRAM_ACCESS_TOKEN) return;
  const response = await fetch(`https://graph.instagram.com/v26.0/${accountId}/messages`, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${env.INSTAGRAM_ACCESS_TOKEN}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ recipient: { id: recipientId }, message: { text } })
  });
  if (!response.ok) console.log(JSON.stringify({ event: "instagram_reply_failed", status: response.status }));
}

async function processTextMessage(event: MessagingEvent, env: Env): Promise<void> {
  const senderId = event.sender?.id;
  const accountId = event.recipient?.id;
  const text = event.message?.text?.trim();
  if (!senderId || !accountId || !text) return;

  if (/^IP-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(text.toUpperCase())) {
    const now = Date.now();
    const pairing = await env.INBOX_DB.prepare(
      "SELECT device_id FROM pairing_codes WHERE code = ? AND expires_at > ? AND used_at IS NULL"
    ).bind(text.toUpperCase(), now).first<{ device_id: string }>();
    if (!pairing) {
      await sendInstagramMessage(env, accountId, senderId, "That pairing code is invalid or expired. Generate a new code in InstaPin.");
      return;
    }

    await env.INBOX_DB.batch([
      env.INBOX_DB.prepare(
        "INSERT INTO instagram_identities(sender_id, device_id, paired_at) VALUES(?, ?, ?) ON CONFLICT(sender_id) DO UPDATE SET device_id = excluded.device_id, paired_at = excluded.paired_at"
      ).bind(senderId, pairing.device_id, now),
      env.INBOX_DB.prepare("UPDATE pairing_codes SET used_at = ? WHERE code = ?").bind(now, text.toUpperCase())
    ]);
    await sendInstagramMessage(env, accountId, senderId, "Connected to InstaPin. Send any public Reel here and it will wait in your app.");
    return;
  }

  const pending = await env.INBOX_DB.prepare(
    "SELECT inbox_item_id FROM pending_notes WHERE sender_id = ? AND expires_at > ?"
  ).bind(senderId, Date.now()).first<{ inbox_item_id: string }>();
  if (!pending) {
    const identity = await env.INBOX_DB.prepare(
      "SELECT device_id FROM instagram_identities WHERE sender_id = ?"
    ).bind(senderId).first<{ device_id: string }>();
    if (!identity) return;
    await env.INBOX_DB.prepare(
      "INSERT INTO staged_notes(sender_id, note, expires_at) VALUES(?, ?, ?) ON CONFLICT(sender_id) DO UPDATE SET note = excluded.note, expires_at = excluded.expires_at"
    ).bind(senderId, text.slice(0, 2000), Date.now() + 90_000).run();
    return;
  }

  await env.INBOX_DB.batch([
    env.INBOX_DB.prepare("UPDATE inbox_items SET note = ?, acknowledged_at = NULL WHERE id = ?")
      .bind(text.slice(0, 2000), pending.inbox_item_id),
    env.INBOX_DB.prepare("DELETE FROM pending_notes WHERE sender_id = ?").bind(senderId)
  ]);
  await sendInstagramMessage(env, accountId, senderId, "Note added. Open InstaPin whenever you are ready to organize it.");
}

async function processReelMessage(event: MessagingEvent, env: Env): Promise<void> {
  const senderId = event.sender?.id;
  const accountId = event.recipient?.id;
  const messageId = event.message?.mid;
  const attachment = event.message?.attachments?.find(item => item.type === "ig_reel");
  const reelUrl = attachment?.payload?.url;
  if (!senderId || !accountId || !messageId || !reelUrl) return;

  const identity = await env.INBOX_DB.prepare(
    "SELECT device_id FROM instagram_identities WHERE sender_id = ?"
  ).bind(senderId).first<{ device_id: string }>();
  if (!identity) {
    await sendInstagramMessage(env, accountId, senderId, "Open InstaPin and connect Instagram Chat Capture before sending Reels.");
    return;
  }

  const now = Date.now();
  const staged = await env.INBOX_DB.prepare(
    "SELECT note FROM staged_notes WHERE sender_id = ? AND expires_at > ?"
  ).bind(senderId, now).first<{ note: string }>();
  const initialNote = event.message?.text?.trim().slice(0, 2000) || staged?.note || "";
  const existing = await env.INBOX_DB.prepare(
    "SELECT id FROM inbox_items WHERE meta_message_id = ?"
  ).bind(messageId).first<{ id: string }>();
  const itemId = existing?.id ?? crypto.randomUUID();
  await env.INBOX_DB.batch([
    env.INBOX_DB.prepare(
      "INSERT OR IGNORE INTO inbox_items(id, device_id, meta_message_id, instagram_url, reel_video_id, title, note, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
    ).bind(
      itemId,
      identity.device_id,
      messageId,
      reelUrl,
      attachment?.payload?.reel_video_id ?? null,
      attachment?.payload?.title?.slice(0, 1000) ?? "",
      initialNote,
      now
    ),
    env.INBOX_DB.prepare("DELETE FROM staged_notes WHERE sender_id = ?").bind(senderId),
    env.INBOX_DB.prepare(
      "INSERT INTO pending_notes(sender_id, inbox_item_id, expires_at) VALUES(?, ?, ?) ON CONFLICT(sender_id) DO UPDATE SET inbox_item_id = excluded.inbox_item_id, expires_at = excluded.expires_at"
    ).bind(senderId, itemId, now + NOTE_TTL_MS)
  ]);
  await sendInstagramMessage(
    env,
    accountId,
    senderId,
    initialNote ? "Reel and note saved. Open InstaPin to view it." : "Reel saved. Reply with a board name or note within 10 minutes."
  );
}

async function processWebhook(payload: any, env: Env): Promise<void> {
  const events: MessagingEvent[] = Array.isArray(payload?.entry)
    ? payload.entry.flatMap((entry: any) => Array.isArray(entry?.messaging) ? entry.messaging : [])
    : [];
  for (const event of events) {
    if (event.message?.is_echo) continue;
    await processReelMessage(event, env);
    await processTextMessage(event, env);
  }
}

async function createPairing(env: Env): Promise<Response> {
  const deviceId = crypto.randomUUID();
  const deviceToken = randomToken();
  let code = randomPairingCode();
  for (let attempt = 0; attempt < 4; attempt++) {
    const existing = await env.INBOX_DB.prepare("SELECT code FROM pairing_codes WHERE code = ?").bind(code).first();
    if (!existing) break;
    code = randomPairingCode();
  }
  const now = Date.now();
  await env.INBOX_DB.batch([
    env.INBOX_DB.prepare("INSERT INTO devices(id, token_hash, created_at) VALUES(?, ?, ?)")
      .bind(deviceId, await sha256(deviceToken), now),
    env.INBOX_DB.prepare("INSERT INTO pairing_codes(code, device_id, expires_at) VALUES(?, ?, ?)")
      .bind(code, deviceId, now + PAIRING_TTL_MS)
  ]);
  return jsonResponse({
    deviceToken,
    pairingCode: code,
    expiresAt: now + PAIRING_TTL_MS,
    instagramUsername: "instapin_app",
    instagramChatUrl: "https://ig.me/m/instapin_app"
  }, 201);
}

async function handleApi(request: Request, url: URL, env: Env): Promise<Response | null> {
  if (request.method === "POST" && url.pathname === "/v1/pairing") return createPairing(env);

  const deviceId = await authorizedDevice(request, env);
  if (!deviceId) return jsonResponse({ error: "Unauthorized" }, 401);

  if (request.method === "GET" && url.pathname === "/v1/pairing/status") {
    const identity = await env.INBOX_DB.prepare(
      "SELECT paired_at FROM instagram_identities WHERE device_id = ?"
    ).bind(deviceId).first<{ paired_at: number }>();
    return jsonResponse({ paired: Boolean(identity), pairedAt: identity?.paired_at ?? null });
  }

  if (request.method === "GET" && url.pathname === "/v1/inbox") {
    const after = Math.max(0, Number(url.searchParams.get("after") ?? 0));
    const result = await env.INBOX_DB.prepare(
      "SELECT id, instagram_url AS instagramUrl, title, note, created_at AS createdAt FROM inbox_items WHERE device_id = ? AND acknowledged_at IS NULL AND created_at > ? ORDER BY created_at ASC LIMIT 100"
    ).bind(deviceId, after).all();
    return jsonResponse({ items: result.results });
  }

  const acknowledgment = url.pathname.match(/^\/v1\/inbox\/([0-9a-f-]+)\/ack$/i);
  if (request.method === "POST" && acknowledgment) {
    await env.INBOX_DB.prepare(
      "UPDATE inbox_items SET acknowledged_at = ? WHERE id = ? AND device_id = ?"
    ).bind(Date.now(), acknowledgment[1], deviceId).run();
    return new Response(null, { status: 204 });
  }

  if (request.method === "DELETE" && url.pathname === "/v1/device") {
    await env.INBOX_DB.prepare("DELETE FROM devices WHERE id = ?").bind(deviceId).run();
    return new Response(null, { status: 204 });
  }

  return null;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname.startsWith("/v1/")) {
      return await handleApi(request, url, env) ?? new Response("Not Found", { status: 404 });
    }

    if (request.method === "GET" && url.pathname === "/webhook") {
      const mode = url.searchParams.get("hub.mode");
      const token = url.searchParams.get("hub.verify_token");
      const challenge = url.searchParams.get("hub.challenge");
      if (mode === "subscribe" && token && equal(token, env.INSTAGRAM_VERIFY_TOKEN) && challenge) {
        return new Response(challenge, { status: 200 });
      }
      return new Response("Forbidden", { status: 403 });
    }

    if (request.method === "POST" && url.pathname === "/webhook") {
      const body = await request.text();
      const signature = request.headers.get("X-Hub-Signature-256");
      const validInstagramSignature = await hasValidSignature(body, signature, env.INSTAGRAM_APP_SECRET);
      const validMetaSignature = env.META_APP_SECRET
        ? await hasValidSignature(body, signature, env.META_APP_SECRET)
        : false;
      if (!validInstagramSignature && !validMetaSignature) {
        return new Response("Unauthorized", { status: 401 });
      }
      const payload = JSON.parse(body);
      ctx.waitUntil(processWebhook(payload, env));
      return new Response("EVENT_RECEIVED", { status: 200 });
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return jsonResponse({ status: "ok" });
    }

    if (request.method === "GET" && url.pathname === "/oauth/callback") {
      return legalPage("Instagram Connected", "<p>You may close this window and return to InstaPin.</p>");
    }

    if (request.method === "GET" && url.pathname === "/privacy") {
      return legalPage(
        "Privacy Policy",
        `<h2>What InstaPin does</h2><p>InstaPin helps people save and organize Instagram links without modifying Instagram or downloading videos. The optional Instagram chat capture receives only messages and links that a person deliberately sends to the InstaPin professional account.</p><h2>Data we process</h2><p>We may process an Instagram-scoped account identifier, message identifiers, Reel or post links, captions and metadata supplied by Meta, notes sent in the chat, pairing status, and synchronization timestamps. We do not request an Instagram password, read an Instagram feed, or access unrelated conversations.</p><h2>How data is used</h2><p>Data is used only to pair the sender with their InstaPin installation, transfer selected saves to the app, prevent duplicates, and respond to the sender. InstaPin keeps the personal library on the user’s Android device. Optional AI analysis uses the provider selected by the user and is subject to that provider’s terms.</p><h2>Sharing and retention</h2><p>We do not sell personal data or use it for advertising. Cloud service providers process minimal data solely to operate the service. Cloud inbox records are retained only as needed for synchronization, security, and deletion requests.</p><h2>Your choices</h2><p>Users can disconnect Instagram capture and request deletion of cloud-linked data at any time by following the data-deletion instructions.</p>`
      );
    }

    if (request.method === "GET" && url.pathname === "/terms") {
      return legalPage(
        "Terms of Service",
        `<h2>Service</h2><p>InstaPin is an independent productivity application for organizing links and metadata that users choose to save. InstaPin is not affiliated with, endorsed by, or sponsored by Instagram or Meta.</p><h2>User responsibilities</h2><p>Users must follow applicable laws and Instagram’s terms, send only content they are permitted to access, and keep their device and credentials secure. The service must not be used for spam, harassment, infringement, or unauthorized access.</p><h2>Content and availability</h2><p>Users retain ownership of their notes and saved references. Original posts remain controlled by their creators and Instagram and may become unavailable. The service is provided without a guarantee that every third-party link or free cloud integration will remain available.</p><h2>Changes and termination</h2><p>Users may stop using the service and request deletion at any time. We may limit abusive use or change the service to maintain security and compliance.</p>`
      );
    }

    if (request.method === "GET" && url.pathname === "/data-deletion") {
      return legalPage(
        "Data Deletion Instructions",
        `<h2>Request deletion</h2><p>Send an email from an address you control to <a href="mailto:connect@aryanventures.co.in?subject=InstaPin%20data%20deletion">connect@aryanventures.co.in</a> with the subject “InstaPin data deletion”. Include the Instagram username connected to InstaPin. Do not include your Instagram password or access token.</p><h2>What happens next</h2><p>We will verify the request, disconnect the Instagram-scoped identity, and delete associated pairing records, pending chat notes, and unsynchronized cloud inbox records. Locally stored Pins remain on the user’s device and can be deleted inside the app. A confirmation will be sent after completion.</p>`
      );
    }

    return new Response("Not Found", { status: 404 });
  }
};
