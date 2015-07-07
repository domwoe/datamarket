# Week 15 Progress

## Completed steps
1. Rewrote protocol to support multiple HTLCs open at the same time.
Implemented intermittent update rounds with batches between server and client:
each component asks for an update round lock from the peer when no other round
is open, then updates are pushed. New updates are added to a blocking queue.
When round is over, it is signaled to the peer, and the peer can then start
its update round if needed.
2. Preliminary testing of modified protocol.
3. Started integration of protocol in the 3-component system.

## Next steps
1. Complete Android device registration to hub. Test on device.
2. Implement flow messages between the buyer, hub, and devices.