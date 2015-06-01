# Week 10 Progress

## Completed steps
1. Finished coding up the HTLC client and server channel classes that make the transitions of the HTLC state machines.
2. Added transaction broadcast scheduler.
3. Extended the bitcoinj TwoWayChannelMessage protobufs for HTLC messages (getting the HTLC refund signed, moving the transactions and their sigs back and forth between the client and the server).
Walked the states manually to check message exchange steps.
4. Integrated the processing of new Protobuf messages in the channel classes.

## Next steps
1. Debug the protocol after testing.
2. Schedule the refund/settlement/forfeit transactions for automated broadcasting.
3. Start integration in the 3-component application.