# Week 9 Progress

## Completed steps
1. Started HTLC client and server channel classes that given a received message, checks its type
and moves the state machines between states.
2. Completed TCP Server that listens for incoming TCP connections, establishes a micropayment
channel given parameters and passes Protobuf messages down to the channel classes.
3. Completed TCP client that given channel parameters, establishes connection with the
TCP Server.
4. Tested channel creation.
5. Completed full sequence diagram for the HTLC based payments.

## Next steps
1. Complete what is left of the client and server channel classes to run a full HTLC based payment on the regtest network.
2. Create unit tests.
3. Create class that watches the blockchain and triggers transaction broadcasting at appropiate times.

## Stretch goals
1. Start integrating the developed protocol into the buyer's application.
2. Continue with Hub and Android app integration.