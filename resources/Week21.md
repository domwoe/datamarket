# Progress Week 21

## Completed steps
1. Fixed synchronous rounds of updates between the 3 components. They're now split.
2. Wrote HubMessageFilter which performs message analysis on the Hub, recomposes messages
and routes them to the correct buyers/devices.
3. Completed secret revealing on the path. When the device reveals a secret to the hub, the hub
forwards it immediately after checking its validity.
4. A lot of bug-fixing: synchronization, incomplete flow messages, switch fallthroughs, etc.

## What works
1. Complete micropayment channel setup on the path.
2. Select queries. (returns from the hub's book-keeping of all available sensors)
3. Buy queries. (complete HTLC setup on the path, revealing the secrets works as well)

## What is missing
1. Actual forwarding of data. The device only gets callbacks from the sensor listeners, cannot get new data on request (cache it?). Is it ok to send the data after receiving the updated settlement/forfeiture TXs in a server update round?
2. Android app: allow price setting for each sensor, not only bulk-pricing.

## Current issues
1. Don't like the idea of forwarding the flow messages between the 2 hub subcomponents in a 3-level locking. Can we somehow make it async?