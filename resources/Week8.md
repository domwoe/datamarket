# Week 8 Progress

## Complete steps
1. Discussed protocol issues such as having multiple HTLCs active on the same teardown transaction or hash referencing.
2. Rewrote the state machine with solutions to the avove problems in mind.
3. Refactored the client payment channel with separate HTLC state instances. Included in both the client and server channel classes a hashmap referencing each HTLC state instance by a UUID.
4. Created sequence diagram that depicts the protocol steps.

## Next steps
1. Build driver-classes that make the transition of the built finite state machines between states.
2. Build classes that allow running the protocol on the network.
3. Test on the local Bitcoin network.