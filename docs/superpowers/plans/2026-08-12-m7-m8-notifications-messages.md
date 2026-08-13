# M7 notifications + M8 messages — plan

Eric signed off on both ("go with both", 2026-08-12). One transport design carries the pair.

## Protocol: one envelope, sealed bodies

A single new control message, `mirror`, whose `data` is the per-pair-key-sealed JSON of a
`MirrorEvent`. Notification text and SMS bodies are E2E-encrypted exactly like clip payloads;
the TLS layer sees only an opaque envelope. Old peers drop the unknown type (existing
behavior), so 0.2.x ↔ 0.3.x pairs keep clipboard/file sync working.

`MirrorEvent` subtypes (kotlinx sealed, discriminator `t`; unknown subtypes dropped on decode):

- `NotifPosted(key, app, title, text, whenMs, canReply)` — phone → desktop
- `NotifReply(key, text)` — desktop → phone (RemoteInput send)
- `SmsQueryThreads` / `SmsThreads(threads)` — desktop asks, phone answers
- `SmsQueryThread(threadId)` / `SmsMessages(threadId, messages)`
- `SmsSend(to, body)` / `SmsSent(ok, to)`

`MirrorEngine` (jvmShared): peers map + `send(peerId?, event)` + `onRemoteMessage` → typed
`onEvent(peerId, event)` callback. `ConnectionManager` gains an optional `mirror` param and
routes `ControlMessage.Mirror` to it; peers registered/removed alongside the other engines.

## Android

- `NotifMirrorService : NotificationListenerService` — filters own package, ongoing, group
  summaries; extracts app label/title/text; remembers RemoteInput-capable actions per key
  (LRU 64) for replies; forwards `NotifPosted`. `NotifReply` → RemoteInput results via the
  stored action's PendingIntent.
- `SmsBridge` — threads derived from the newest 500 rows of `content://sms` (address-only:
  contact names would need READ_CONTACTS, deliberately not requested); thread view = last 50;
  send via SmsManager (multipart-aware); ContentObserver pushes a fresh thread snapshot
  (debounced) so new texts appear on the desktop unprompted.
- UI: two opt-in cards — notification access (Settings deep link) and SMS permissions
  (runtime request). Both features are dormant until granted; everything else works without.
- Permission surface (the sign-off): `BIND_NOTIFICATION_LISTENER_SERVICE` (user toggle),
  `READ_SMS`, `SEND_SMS`. F-Droid-fine; Play would reject SMS — we don't target Play.

## Desktop

- Native macOS notification per `NotifPosted` (Compose `TrayState.sendNotification`) plus a
  Notifications tab: recent list, inline reply field for `canReply` rows.
- Messages tab: thread list → thread view → compose + send. Opens with a refresh request;
  live updates ride the observer pushes.
- Tabs (Activity · Notifications · Messages) replace the single Activity area.

## Verification levers (no real contacts ever)

- Shell-posted notification (`cmd notification post`) exercises listener → Mac.
- A clipsync debug notification with a RemoteInput action + logging receiver proves the
  reply path end-to-end synthetically.
- SMS read path asserted by counts only (message bodies never enter harness logs); live send
  only to Eric's own number, clearly labeled, if his number is readable — else left to Eric.
- Listener access + runtime grants via adb (`cmd notification allow_listener`, `pm grant`) —
  authorized by the sign-off, reversible.

## Out of scope (documented)

Dismissal sync, MMS/RCS, contact names, notification icons/images, reply to non-RemoteInput
notifications. Version bumps to 0.3.0 when both land verified.
