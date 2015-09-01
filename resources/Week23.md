# Progress Week 23

## Completed steps
1. Made some modifications to the flow messages in all components to support the data transfer.
2. Hooked the sensor data retrieval to the callbacks in the HTLC remote service running on the device.
3. Completed the data transfer async, out of update rounds, since it does not interfere with the
actual teardown transaction.
3. Wrote introduction, related work and provided the background for Bitcoin, micropayment, and HTLCs.
3. Read Akselrod's proposal but seems sloppy regarding the actual details. Don't understand
how he gets away without the minimal trust on the server-side with the 2-step jump in the signature computations.

## Next steps
1. Move the data forwarding to the HTLCRevealSecret protobuf message (as proposed by Christian).
2. Continue with the report. Plan is to have a complete draft by next week Wednesday (except the Evaluation chapter).
3. Hook app pricing to the actual pricing signaled to the hub (atm hardcoded).
4. Hook wallet update callback to app view.

## Questions
1. Are figures in the final report ok to be colorful as in the midterm presentation or use only black ?
2. Alex Akselrod's proposal ?