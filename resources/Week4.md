#Week 4 Progress

## Resources

1. http://developer.android.com/tools/studio/index.html
2. http://developer.android.com/reference/packages.html
3. https://en.bitcoin.it/wiki/Contracts#Example_5:_Trading_across_chains

## Completed steps
1. Read Android documentation about views, activities, life cycle, etc.
2. Read about Android sensors and how to hook up listeners to the data they generate.
3. Built a basic app that does the following:
	- on the first start, it allows the user to set up a price for a sensor data entry and averaged data entry over a second
	- retrieves a list of all available sensors and displays on the main screen
	- allows sharing the data through a simple switch interface
	- settings allow the adjustement of the previously established quotas
4. Tested app with SensorSimulator (https://code.google.com/p/openintents/wiki/SensorSimulator). Sensor data is injected in the emulator and captured by the app when sharing is enabled.
5. Looked into the micropayments package of BitcoinJ and tried to understand how it is setup, so we can extend it for our variant of HTLC.

## Next steps
1. Extend app to include location services
2. Validate price input
3. Build a simple Hub. Build connection between app and Hub, implement protocol messages.
4. Make progress on modified HTLC (look at Transactions to accomodate it in more detail).