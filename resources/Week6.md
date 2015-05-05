# Week 6 Progress

## Resources
1. https://bitcoinj.github.io/javadoc/0.12.3
2. https://bitcoin.org/en/developer-guide#micropayment-channel
3. https://bitcoinj.github.io/working-with-micropayments
4. https://bitcoin.org/en/developer-examples
5. https://en.bitcoin.it/wiki/Script

## Completed steps
1. Dwelved into bitcoinj code to check how HTLC can be integrated with the Micropayments code
2. With Christian's help:
	* Fixed the HTLC setup scriptPub (use non-verify versions of signature verification ops)
	* Created proper input tx for the refund branch of the HTLC script
	* Created proper input tx for the second branch, which includes revealing the secret to be hashed
3. Started building finite state machine

## Next steps
1. Finish finite state machine
2. Integrate with Micropayment channels code from BitcoinJ