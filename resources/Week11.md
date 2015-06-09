# Week 11 Progress

## Completed steps
1. Modified HTLCState and protobuf messages to use secret's hash as identifier in the ByteString form. 
2. Created wallet, transfered 10 BTC to client.
3. Fixed initial channel payment of dust value in order to avoid an unsettleable state. Created new HTLC
setupTx protobuf message for this.
4. Fixed signature bug in channel setup (pay more attention to signature ordering as well).
5. Successfully tested opening HTLC micropayment channel.
6. Fixed sequence number bug for timelocked tx's.
7. Fixed teardown sig verification bug on HTLC server.
8. Discovered bug on HTLC refund connection. Looked into it, no solution yet.

## Next steps
1. Schedule the refund/settlement/forfeit transactions for automated broadcasting.
2. Start integration in the 3-component application.