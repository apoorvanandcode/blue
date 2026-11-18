# Phase 8: mark-as-read feasibility test

**Answer: No.** Google Messages keeps its own unread state; marking an SMS as read in the SMS provider doesn't clear it, on the emulator or on the phone.

**Question (from the build prompt):** if an app holding the default SMS role marks an SMS as read in the SMS provider, does Google Messages clear its unread state for that SMS after it becomes the default again?

**How it was tested:** a separate throwaway app, `app.blue.readtest` (module `readtest/`), never part of Blue. It can hold the default-SMS role, saves any SMS that arrives while it's default, and never sends or deletes anything.

1. Google Messages is the default; a new SMS arrives and shows as unread.
2. Make Blue read test the default SMS app.
3. Mark the SMS as read: `read=1, seen=1` written to `content://sms/<id>` (confirmed by reading it back).
4. Make Google Messages the default again.
5. Look in Google Messages straight away, and again after force-stopping it and waiting a minute.

## Result on the emulator (Pixel 7 image, Android 16, 18 Nov 2026)

**No.** Google Messages kept showing the SMS as unread (bold, unread badge), both straight away and after a full restart. The SMS provider still said `read=1` for all three test SMS, so Messages didn't reset the provider either; it just keeps its own unread state and doesn't take it from the provider.

## Result on the phone (Android 16, 18 Nov 2026)

**No.** Same as the emulator: after Blue read test marked the SMS as read and Google Messages was made the default again, Google Messages still showed it as unread. Reported by the user.

## What this means for a future "mark as read" feature

Marking SMS as read from Blue would change the system SMS database but not what Google Messages shows. To clear unread in Google Messages, it has to be done in Google Messages (or Blue would have to stay the default SMS app, which v1 rules out).
