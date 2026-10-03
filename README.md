# shuriken-android
Android companion app for the WiFi-Shuriken.

## Reading SD files

Connect to Shuriken as usual, then open **Files** and tap **Refresh files**.
Tap a file to choose where Android should save a copy. Reads use Android's MTP
API on a dedicated background worker and USB connection, independent of the
Console and NMEA CDC connections. No additional storage permission is needed.
The app does not upload or delete files on Shuriken.

The current firmware exposes files in the SD root, not nested directories.
During a download the firmware holds the SD mutex, so SD logging can be delayed.
Refresh before downloading a growing log to update the displayed size; a read is
not a continuously synchronized copy. A failed transfer may leave a partial
destination file. Refresh/reconnect before retrying.

MTP transfers are native blocking calls: leaving the Files tab does not cancel
a download. Disconnect invalidates the file list immediately; native cleanup
runs on the MTP worker after the in-flight call returns. Console draining and
NMEA transmission remain on their existing independent paths.

## WiGLE uploads

Open **WiGLE**, sign in at https://wigle.net/activate on another screen, then
tap **Scan activation QR**. The QR text must be
`Username:API_name:API_token`. Camera permission is requested only when scanning.
The app stores the credentials using AES-GCM with an Android Keystore key;
ciphertext is kept in app-private storage excluded from backups. Tokens are
never displayed or logged. **Remove credentials** removes the local copy, not
the token at WiGLE.

Save a CSV log through **Files**, then choose that saved file in **WiGLE**.
**Upload to WiGLE** asks for confirmation before sending its complete network
and location data. Uploads use WiGLE's authenticated v2 multipart endpoint
(`POST https://api.wigle.net/api/v2/file/upload`) and stream a private, validated
snapshot of the CSV, gzip-compressed and named after the selected file with
`.gz` appended (for example, `survey.csv.gz`). This app caps uncompressed
snapshots at 100 MiB. Source files and files
on Shuriken are not changed or deleted.

You can also tap a file in **Files** and choose **Upload to WiGLE**, without
saving it first. Configure credentials in **WiGLE**;
the Files tab still asks for upload confirmation. Both upload paths record
WiGLE-accepted uploads in a local SQLite database by original filename and
uncompressed byte size. Matching SD files are hidden by default; turn on
**Show uploaded files** to view, save, or deliberately re-upload them. A changed
size makes a file visible again. This is not a content hash: different files
with the same name and size match, regardless of device or WiGLE account.
Only uploads made after this feature was added are recorded. Acceptance means
queued at WiGLE, not finished processing. Refresh before uploading a file that
is still being logged; files that change during the MTP read are rejected.

For a batch, check individual files or use **Select all** (visible, non-diagnostic
files only), then **Upload selected**. **Clear selection** deselects them. The
WiGLE tab's CSV picker also supports multiple documents. RESETLOG.CSV cannot be
selected for uploading. Each file is downloaded, validated, compressed, sent,
and recorded separately; accepted files leave the selection. The batch stops
on any failure or cancellation and leaves unsent files selected (SD selections
are invalidated by refresh/disconnect). No automatic retry or resume occurs.

Uploads run sequentially, with a one-second pause between successful requests.
This does not bypass WiGLE's daily quota. HTTP 429 stops the batch and pauses
new uploads for that account until `Retry-After` (seconds or HTTP date), if
provided. Without a valid header, the app uses a conservative 24-hour **local**
cooldown, not an assumed server reset time. Cooldowns survive app restarts;
expiry enables manual uploading but never restarts a queue. HTTP 400, 401/403,
418, and 500 have specific error messages; HTTP errors always override a
misleading JSON `success: true`. Transaction IDs accept documented `transId`
and legacy lowercase `transid` fields. Before retrying interrupted or server
error uploads, check WiGLE history to avoid duplicates.

Keep the app open until completion. Uploads survive rotation and tab changes,
but are not a persistent background queue. Cancellation interrupts the network
call. Requests are not automatically retried and credentials are not forwarded
through redirects. If cancelled or interrupted, check WiGLE upload history before
retrying: WiGLE may already have received the file. Acceptance and transaction
IDs mean the upload is queued for processing, not that processing is complete.

Uploads use the working v2 endpoint after v3 uploads returned HTTP 404 in this
app and were reported failing in WiGLE's interactive docs. Basic authentication and
the `file` part with original basename plus `.gz` remain unchanged. The app
does not send the v3-only `fileSize` field or opt into commercial donation.
Local history continues to use the uncompressed CSV size.
