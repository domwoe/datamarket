# Progress Week 22

## Completed steps
1. Fixed app layout on the device (switch buttons were out of view, wallet value bar not displayed, etc)
2. Caching of data in the app from the callbacks. 
3. Added flow msg for the data from the app up to the buyer, still need to discuss at what point to request it from device: request on HTLC setup and cache VS request only when revealing secret and forward immediately.
4. Wrote abstract section of report.

## Next steps
1. Continue with report writing (write as much as possible and send out immediately to get feedback).
2. Allow price setting for each sensor in the app.
3. Test data receiving on the buyer once data request moment is fixed.
4. Move sensor processing in a service if UI frames are being skipped during testing.