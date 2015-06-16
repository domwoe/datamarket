# Week 12 Progress

## Completed steps
1. Fixed bug for secret hash comparison: switched to using String instead of ByteString.
2. Added new protobuf message that confirms server received updated teardown (payment ack).
3. Fully tested HTLC micropayment basic use case.
4. Tested channel refundTx broadcasting at the right time.
5. Tested receiver teardown broadcasting at the right time (before channel refundTx becomes valid).
6. Tested sender HTLC refund Tx broadcasting at the right time.
7. Tested receiver settlement broadcasting at the right time.
8. Discussed with Mike Hearn the options for getting a callback on the sender part when the teardownTx is broadcast by the receiver. Seems like the best option is to write a custom filter provider that inserts the hash of the teardown Tx's.


## Next steps
1. Prepare presentation.
2. Write custom filter provider for teardownTx hashes.
3. Create chain transaction monitor and schedule forfeit properly. Cancel scheduled settlement and refund Tx's accordingly.
4. Add proper error messages between HTLC peers.
5. Start integration in the 3-component application.