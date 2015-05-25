package org.bitcoinj.channels.htlc;


import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.ECKey.KeyIsEncryptedException;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.protocols.channels.IPaymentChannelClient;
import org.bitcoinj.protocols.channels.PaymentChannelCloseException.CloseReason;
import org.bitcoinj.protocols.channels.PaymentIncrementAck;
import org.bitcoinj.protocols.channels.ValueOutOfRangeException;
import org.bitcoinj.utils.Threading;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;
import static com.google.common.base.Preconditions.checkState;


import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;

import net.jcip.annotations.GuardedBy;

/**
 * This class handles the creation of an HTLC payment channel.
 * Implements the IPaymentChannelClient interface
 * @author frabu
 *
 */
public class HTLCPaymentChannelClient implements IPaymentChannelClient {
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCPaymentChannelClient.class);
	private static final int CLIENT_MAJOR_VERSION = 1;
	private final int CLIENT_MINOR_VERSION = 0;
	private static final int SERVER_MAJOR_VERSION = 1;
	 
	private final ECKey clientPrimaryKey;
	private final ECKey clientSecondaryKey;
	
	private final long timeWindow;
	
	protected final ReentrantLock lock = Threading.lock("channelclient");
	 
	@GuardedBy("lock") private final ClientConnection conn;
	@GuardedBy("lock") private HTLCChannelClientState state;
	
	private enum InitStep {
		WAITING_FOR_CONNECTION_OPEN,
        WAITING_FOR_VERSION_NEGOTIATION,
        WAITING_FOR_INITIATE,
        WAITING_FOR_REFUND_RETURN,
        WAITING_FOR_CHANNEL_OPEN,
        CHANNEL_OPEN,
        WAITING_FOR_CHANNEL_CLOSE,
        CHANNEL_CLOSED,
	}
	@GuardedBy("lock") 
	private InitStep step = InitStep.WAITING_FOR_CONNECTION_OPEN;
	 
	// The wallet associated with this channel
    private final Wallet wallet;
    private final Coin value;
    
    public HTLCPaymentChannelClient(
    	Wallet wallet,
    	ECKey clientPrimaryKey,
    	ECKey clientSecondaryKey,
    	Coin value,
    	long timeWindow,
    	ClientConnection  conn
	) {
    	this.wallet = wallet;
    	this.clientPrimaryKey = clientPrimaryKey;
    	this.clientSecondaryKey = clientSecondaryKey;
    	this.value = value;
    	this.timeWindow = timeWindow;
    	this.conn = conn;
    }
    
    /**
     * Called to indicate the connection has been opened and messages can 
     * now be generated for the server.
     * Generates a CLIENT_VERSION message for the server. Server responds
     * with its version
     */
    @Override
    public void connectionOpen() {
    	lock.lock();
    	try {
    		step = InitStep.WAITING_FOR_VERSION_NEGOTIATION;
    		
    		Protos.ClientVersion.Builder versionNegotiationBuilder = Protos.ClientVersion.newBuilder()
                    .setMajor(CLIENT_MAJOR_VERSION)
                    .setMinor(CLIENT_MINOR_VERSION)
                    .setTimeWindowSecs(timeWindow);
    		conn.sendToServer(Protos.TwoWayChannelMessage.newBuilder()
                    .setType(Protos.TwoWayChannelMessage.MessageType.CLIENT_VERSION)
                    .setClientVersion(versionNegotiationBuilder)
                    .build());
    	} finally {
    		lock.unlock();
    	}
    }
    
    @Override
    public void receiveMessage(Protos.TwoWayChannelMessage msg) 
		throws InsufficientMoneyException {
   
    	lock.lock();
        try {
        	Protos.Error.Builder errorBuilder;
            CloseReason closeReason;
        	switch (msg.getType()) {
        		case SERVER_VERSION:
        			checkState(
        				step == InitStep.WAITING_FOR_VERSION_NEGOTIATION && 
        				msg.hasServerVersion()
    				);
        			if (msg.getServerVersion().getMajor() != SERVER_MAJOR_VERSION) {
        				errorBuilder = Protos.Error.newBuilder()
    						.setCode(Protos.Error.ErrorCode.NO_ACCEPTABLE_VERSION);
        				closeReason = CloseReason.NO_ACCEPTABLE_VERSION;
        				break;
        			}
        			log.info("Got version handshake, awaiting INITIATE");
        			step = InitStep.WAITING_FOR_INITIATE;
                    return;
        		case INITIATE:
        			checkState(
    					step == InitStep.WAITING_FOR_INITIATE && 
    					msg.hasInitiate()
					);
        			Protos.Initiate initiate = msg.getInitiate();
        			errorBuilder = Protos.Error.newBuilder();
        			closeReason = receiveInitiate(initiate, value, errorBuilder);
        			if (closeReason == null) {
        				return;
        			}
        			log.error(
    					"Initiate failed with error: {}", 
    					errorBuilder.build().toString()
					);
        			break;
        		default:
                    log.error("Got unknown message type or type that doesn't apply to clients.");
                    errorBuilder = Protos.Error.newBuilder()
                		.setCode(Protos.Error.ErrorCode.SYNTAX_ERROR);
                    closeReason = CloseReason.REMOTE_SENT_INVALID_MESSAGE;
                    break;
        	}
        	conn.sendToServer(Protos.TwoWayChannelMessage.newBuilder()
    			.setError(errorBuilder)
                .setType(Protos.TwoWayChannelMessage.MessageType.ERROR)
                .build());
        	conn.destroyConnection(closeReason);
        } finally {
        	lock.unlock();
        }
    }
    
    // TODO: CONTINUE FROM HERE
    @Nullable
    @GuardedBy("lock")
    private CloseReason receiveInitiate(
		Protos.Initiate initiate,
		Coin contractValue, 
		Protos.Error.Builder errorBuilder
	) {
    	return null;
    }

	@Override
	public void connectionClosed() {
				
	}

	@Override
	public ListenableFuture<PaymentIncrementAck> incrementPayment(Coin arg0,
			@Nullable ByteString arg1, @Nullable KeyParameter arg2)
			throws ValueOutOfRangeException, IllegalStateException,
			KeyIsEncryptedException {
		
		return null;
	}

	@Override
	public void settle() throws IllegalStateException {
				
	}
}
