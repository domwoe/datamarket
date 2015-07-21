# Progress Week 17

## Completed steps
1. Fixed Android external jar libs import runtime errors.
2. Encountered problems with sfl4j logging facade (either crashed app at runtime or
complained about duplicate classes). Cherry-picked duplicated classes and recompressed
jar to get rid of issue.
3. Connected Android device to Wifi. Modified app code to use the local bitcoin network
and the HTLC hub, both running on my laptop. Worked :)
4. Implemented AIDL communication between remote service and app.
5. Managed to run full micropayment channel establishment between Android and hub.

## Current issues
1. How to identify buyers/devices on the Hub when connecting?

## Next steps
1. Fix current issue.
2. Add callback to app to update displayed wallet value through AIDL.
3. Implement buyer/device book-keeping on hub.
4. Implement flow messages.