# Week 16 Progress

## Completed steps
1. Created the 3 separated components: the buyer that connects to the outter hub,
inner hub that is the Android devices connect to, and the Android component.
2. Modified connection between inner hub and Android device, such that:
- The inner hub runs a TCP server
- Android device can initialize the connection
- But the payment protocol itself is inverted: inner hub acts as a client,
Android device as a server(receiver).
3. Wrote an Android background service that starts at device boot-up
and establishes the connection and the micropayment channel to the hub.
4. Spent time on how to connect the two hub subcomponents. First
idea: keep a special TPC connection between the two, but has circular dependency
problem with the current design and hard to mark it from the accepting part.
Other idea: merge the two servers and synchronize them.
5. Spent time looking into the runtime crash of the Android service.
6. Extended the protobuf messages to include node and sensor stats queries.
Implemented simple console application that allows these queries on the buyer side.

## Current issues
1. Android service crashes at runtime on device because it doesn't recognise
classes imported from jars.
2. How to connect the outter and inner hubs.
3. What is the best way to keep track of number of connected sensors and other stats
at high server level.

## Next steps
1. Fix current issues.
2. Test connectivity and channel establishment.
3. Continue implementing flow messages and their processing on all components.