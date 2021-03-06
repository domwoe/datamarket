package org.bitcoinj.channels.htlc.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.bitcoin.paymentchannel.Protos;
import org.bitcoin.paymentchannel.Protos.HTLCBackOff;
import org.bitcoin.paymentchannel.Protos.HTLCData;
import org.bitcoin.paymentchannel.Protos.HTLCFlow;
import org.bitcoin.paymentchannel.Protos.HTLCInit;
import org.bitcoin.paymentchannel.Protos.HTLCInitReply;
import org.bitcoin.paymentchannel.Protos.HTLCPayment;
import org.bitcoin.paymentchannel.Protos.HTLCPaymentReply;
import org.bitcoin.paymentchannel.Protos.HTLCResumeSetup;
import org.bitcoin.paymentchannel.Protos.HTLCRevealSecret;
import org.bitcoin.paymentchannel.Protos.HTLCServerUpdate;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage;
import org.bitcoin.paymentchannel.Protos.HTLCFlow.FlowType;
import org.bitcoin.paymentchannel.Protos.TwoWayChannelMessage.MessageType;
import org.slf4j.LoggerFactory;

public class HTLCHubMessageFilter {
	
	private static final org.slf4j.Logger log = 
		LoggerFactory.getLogger(HTLCHubMessageFilter.class);
	private static final Integer MAX_MESSAGES = 100;
	
	private final ConcurrentHashMap<String, HTLCHubAndroidServer> htlcIdToAndroidMap;
	private final ConcurrentHashMap<String, HTLCHubBuyerServer> htlcIdToBuyerMap;
	private final ConcurrentHashMap<String, HTLCHubAndroidServer> deviceIdToDeviceMap;
	
	private final BlockingQueue<AndroidMessage> androidQueue;
	private final AndroidQueueConsumer androidConsumer;
	
	private final BlockingQueue<BuyerMessage> buyerQueue;
	private final BuyerQueueConsumer buyerConsumer;
	
	class AndroidMessage {
		private TwoWayChannelMessage msg;
		private HTLCHubBuyerServer buyerServer; // origin
		public AndroidMessage(
			HTLCHubBuyerServer buyerServer, 
			TwoWayChannelMessage msg
		) {
			this.buyerServer = buyerServer;
			this.msg = msg;
		}
	}
	
	class BuyerMessage {
		public TwoWayChannelMessage msg;
		public HTLCHubAndroidServer androidServer; // origin
		public BuyerMessage(
			HTLCHubAndroidServer androidServer, 
			TwoWayChannelMessage msg
		) {
			this.androidServer = androidServer;
			this.msg = msg;
		}
	}
	
	public HTLCHubMessageFilter() {
		this.htlcIdToAndroidMap = new ConcurrentHashMap<String, HTLCHubAndroidServer>();
		this.htlcIdToBuyerMap = new ConcurrentHashMap<String, HTLCHubBuyerServer>();
		this.deviceIdToDeviceMap = new ConcurrentHashMap<String, HTLCHubAndroidServer>();
		this.androidQueue = new ArrayBlockingQueue<>(MAX_MESSAGES);
		this.buyerQueue = new ArrayBlockingQueue<>(MAX_MESSAGES);
		this.androidConsumer = new AndroidQueueConsumer(androidQueue);
		this.buyerConsumer = new BuyerQueueConsumer(buyerQueue);
		new Thread(androidConsumer).start();
		new Thread(buyerConsumer).start();
	}
	
	public void putAndroidMsg(
		HTLCHubBuyerServer buyerServer, 
		TwoWayChannelMessage msg
	) {
		try {
			log.info("PUSHING IN ANDROID QUEUE {}", msg);
			androidQueue.put(new AndroidMessage(buyerServer, msg));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public void putBuyerMsg(
		HTLCHubAndroidServer androidServer, 
		TwoWayChannelMessage msg
	) {
		try {
			buyerQueue.put(new BuyerMessage(androidServer, msg));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	class AndroidQueueConsumer implements Runnable {
		private BlockingQueue<AndroidMessage> queue;
		
		public AndroidQueueConsumer(BlockingQueue<AndroidMessage> queue) {
			this.queue = queue;
		}

		@Override
		public void run() {
			try {
				while (true) {
					AndroidMessage queueMsg = queue.take();
					filterMessageForAndroid(queueMsg.buyerServer, queueMsg.msg);
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		private void filterMessageForAndroid(
				HTLCHubBuyerServer fromBuyerServer,	
				TwoWayChannelMessage msg
		) {
			log.info("Received message to be filtered for devices");
			switch (msg.getType()) {
				case HTLC_INIT:
					filterHTLCInitMessageForAndroid(
						fromBuyerServer, 
						msg.getHtlcInit()
					);
					break;
				case HTLC_FLOW:
					filterHTLCFlowMessageForAndroid(
						fromBuyerServer,
						msg.getHtlcFlow()
					);
					break;
				default:
					break;
			}
		}
		
		private void filterHTLCInitMessageForAndroid(
				HTLCHubBuyerServer buyerServer,
				HTLCInit initMsg
		) {
			log.info("Received HTLCINITMessage for Android");
			List<HTLCPayment> paymentList = initMsg.getNewPaymentsList();
			
			// We have to process the payments so we sort all payments by deviceId
			Map<String, List<HTLCPayment>> deviceIdToPaymentsMap = 
				new HashMap<String, List<HTLCPayment>>();
			
			for (HTLCPayment payment: paymentList) {
				String deviceId = payment.getDeviceId();
				String requestId = payment.getRequestId();
				
				log.info("DeviceId: {}", deviceId);
				
				List<HTLCPayment> paymentsForDevice = 
					deviceIdToPaymentsMap.get(deviceId);
				if (paymentsForDevice == null) {
					paymentsForDevice = new ArrayList<HTLCPayment>();
				}
				paymentsForDevice.add(payment);
				deviceIdToPaymentsMap.put(deviceId, paymentsForDevice);
				
				// We register the requestId as being the htlcId for now; update
				// when we see the reply for the same request id
				htlcIdToBuyerMap.put(requestId, buyerServer);
				htlcIdToAndroidMap.put(requestId, deviceIdToDeviceMap.get(deviceId));
			}
			
			for (
				Map.Entry<String, List<HTLCPayment>> entry: 
					deviceIdToPaymentsMap.entrySet()
			) {
				List<HTLCPayment> paymentsToDevice = entry.getValue();
			    		
	    		List<String> requestIdList = new ArrayList<String>();
	    		for (HTLCPayment payment: paymentsToDevice) {
	        		requestIdList.add(payment.getRequestId());
	    		}
	    		
	    		Protos.HTLCInit.Builder htlcInitToDevice = 
					Protos.HTLCInit.newBuilder()
						.addAllNewPayments(paymentsToDevice);
	    		final Protos.TwoWayChannelMessage channelMsg = 
					Protos.TwoWayChannelMessage.newBuilder()
						.setType(MessageType.HTLC_INIT)
						.setHtlcInit(htlcInitToDevice)
						.build();
				log.info("Forwarding to device: {}", entry.getKey());
				log.info("MAPPING: {}", deviceIdToDeviceMap.get(entry.getKey()));
	    		deviceIdToDeviceMap.get(entry.getKey()).receiveMessage(channelMsg);
			}
		}
		
		private void filterHTLCFlowMessageForAndroid(
			HTLCHubBuyerServer buyerServer,
			HTLCFlow flowMsg
		) {
			log.info("Filtering HTLC flow msg for Android");
			switch (flowMsg.getType()) {
				case RESUME_SETUP:
					filterHTLCResumeSetupMessageForAndroid(
						buyerServer, 
						flowMsg.getResumeSetup()
					);
					break;
				default:
					log.error(
						"Received invalid message type " +
						"for Android dev in filter"
					);
					break;
			}
		}
			
		private void filterHTLCResumeSetupMessageForAndroid(
			HTLCHubBuyerServer buyerServer,
			HTLCResumeSetup resumeMsg
		) {
			log.info("Received Resume setup for Android device");
			List<String> allIds = resumeMsg.getHtlcIdList();
			Map<HTLCHubAndroidServer, List<String>> filteredResumeMap = 
				new HashMap<HTLCHubAndroidServer, List<String>>();
			
			for (String htlcId: allIds) {
				log.info("Processing id: {}", htlcId);
				HTLCHubAndroidServer androidServer = htlcIdToAndroidMap.get(htlcId);
				List<String> resumesForAndroid = 
					filteredResumeMap.get(androidServer);
				if (resumesForAndroid == null) {
					resumesForAndroid = new ArrayList<String>();
				}
				resumesForAndroid.add(htlcId);
				// Update map
				filteredResumeMap.put(androidServer, resumesForAndroid);
			}
			
			for (
				Map.Entry<HTLCHubAndroidServer, List<String>> entry: 
					filteredResumeMap.entrySet()
			) {
				Protos.HTLCResumeSetup.Builder htlcResume = 
					Protos.HTLCResumeSetup.newBuilder()
						.addAllHtlcId(entry.getValue());
				Protos.HTLCFlow flow = Protos.HTLCFlow.newBuilder()
					.setType(FlowType.RESUME_SETUP)
					.setResumeSetup(htlcResume)
					.build();
				final Protos.TwoWayChannelMessage channelMsg = 
					Protos.TwoWayChannelMessage.newBuilder()
						.setType(MessageType.HTLC_FLOW)
						.setHtlcFlow(flow)
						.build();
				log.info("Sending RESUME_SETUP FLOW to Android Server");
				entry.getKey().receiveMessage(channelMsg);
			}
		}
	}
	
	class BuyerQueueConsumer implements Runnable {

		private BlockingQueue<BuyerMessage> queue;
		
		public BuyerQueueConsumer(BlockingQueue<BuyerMessage> queue) {
			this.queue = queue;
		}
		
		@Override
		public void run() {
			try {
				while (true) {
					BuyerMessage queueMsg = queue.take();
					filterMessageForBuyer(queueMsg.androidServer, queueMsg.msg);
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		/**
		 * Inspect message and return batched map from htlcId to
		 * TwoWayChannelMessage; These can be then linked to devices/buyers
		 * by looking into the HTLCid To Device/Buyer map in the HubListener class
		 * @param msg
		 * @return
		 */
		public void filterMessageForBuyer(
			HTLCHubAndroidServer fromAndroidServer,
			TwoWayChannelMessage msg
		) {
			switch (msg.getType()) {
				case HTLC_SERVER_UPDATE:
					filterServerUpdateMessageForBuyer(msg.getHtlcServerUpdate());
					break;
				case HTLC_INIT_REPLY:
					filterInitReplyMessageForBuyer(
						fromAndroidServer,
						msg.getHtlcInitReply()
					);
					break;
				case HTLC_FLOW:
					HTLCFlow flowMsg = msg.getHtlcFlow();
					switch (flowMsg.getType()) {
						case DATA:
							filterDataMsgForBuyer(flowMsg.getDataList());
							break;
						default:
							log.info("Received invalid message type: {}", msg);
							break;
					}
					break;
				default:
					log.info("Received invalid message type: {}", msg);
					break;
			}
		}
		
		private void filterDataMsgForBuyer(List<HTLCData> dataMsgList) {
			log.info("Filtering DATA msg for buyer");
			Map<HTLCHubBuyerServer, List<HTLCData>> filteredDataMap =
				new HashMap<HTLCHubBuyerServer, List<HTLCData>>();
			
			for (HTLCData dataMsg: dataMsgList) {
				String htlcId = dataMsg.getId();
				HTLCHubBuyerServer buyerServer = htlcIdToBuyerMap.get(htlcId);
				List<HTLCData> dataForBuyer = filteredDataMap.get(buyerServer);
				if (dataForBuyer == null) {
					dataForBuyer = new ArrayList<HTLCData>();
				}
				dataForBuyer.add(dataMsg);
				filteredDataMap.put(buyerServer, dataForBuyer);
			}
			
			for (Map.Entry<HTLCHubBuyerServer, List<HTLCData>> entry:
					filteredDataMap.entrySet()
			) {
				HTLCFlow.Builder flowMsg = HTLCFlow.newBuilder()
					.setType(FlowType.DATA)
					.addAllData(entry.getValue());
				final Protos.TwoWayChannelMessage msgForBuyer =
					Protos.TwoWayChannelMessage.newBuilder()
						.setType(MessageType.HTLC_FLOW)
						.setHtlcFlow(flowMsg)
						.build();
				entry.getKey().receiveMessage(msgForBuyer);
			}
		}
		
		private void filterInitReplyMessageForBuyer(
			HTLCHubAndroidServer fromAndroidServer,
			HTLCInitReply replyMsg
		) {
			Map<HTLCHubBuyerServer, List<HTLCPaymentReply>> filteredReplyMap = 
				new HashMap<HTLCHubBuyerServer, List<HTLCPaymentReply>>();
			
			for (HTLCPaymentReply reply: replyMsg.getNewPaymentsReplyList()) {
				String requestId = reply.getClientRequestId();
				String htlcId = reply.getId();
				
				// Update the maps
				HTLCHubAndroidServer androidServer = 
					htlcIdToAndroidMap.get(requestId);
				htlcIdToAndroidMap.remove(requestId);
				htlcIdToAndroidMap.put(htlcId, androidServer);
				
				HTLCHubBuyerServer buyerServer =
					htlcIdToBuyerMap.get(requestId);
				htlcIdToBuyerMap.remove(requestId);
				htlcIdToBuyerMap.put(htlcId, buyerServer);
				
				List<HTLCPaymentReply> repliesForBuyer = 
					filteredReplyMap.get(buyerServer);
				if (repliesForBuyer == null) {
					repliesForBuyer = new ArrayList<HTLCPaymentReply>();
				}
				repliesForBuyer.add(reply);
				filteredReplyMap.put(buyerServer, repliesForBuyer);
			}
			
			for (
				Map.Entry<HTLCHubBuyerServer, List<HTLCPaymentReply>> entry: 
					filteredReplyMap.entrySet()
			) {
				HTLCInitReply.Builder initReplyForBuyer = 
					Protos.HTLCInitReply.newBuilder()
						.addAllNewPaymentsReply(entry.getValue());
				final Protos.TwoWayChannelMessage msgForBuyer =
					Protos.TwoWayChannelMessage.newBuilder()
						.setType(MessageType.HTLC_INIT_REPLY)
						.setHtlcInitReply(initReplyForBuyer)
						.build();
				entry.getKey().receiveMessage(msgForBuyer);
			}
		}
		
		// TODO remove from map the htlcs that are removed here
		private void filterServerUpdateMessageForBuyer(
			HTLCServerUpdate updateMsg
		) {
			log.info("Filtering Server Update Msg for Buyer");
			List<HTLCRevealSecret> allSecrets = updateMsg.getRevealSecretsList();
			List<HTLCBackOff> allBackoffs = updateMsg.getBackOffsList();
			
			Map<HTLCHubBuyerServer, List<HTLCBackOff>> filteredBackOffMap = 
				new HashMap<HTLCHubBuyerServer, List<HTLCBackOff>>();
			Map<HTLCHubBuyerServer, List<HTLCRevealSecret>> filteredSecretMap = 
				new HashMap<HTLCHubBuyerServer, List<HTLCRevealSecret>>();
			
			for (HTLCRevealSecret secret: allSecrets) {
				log.info("Filtering secret: " + secret.getSecret());
				HTLCHubBuyerServer server = htlcIdToBuyerMap.get(secret.getId());
				List<HTLCRevealSecret> currentSecrets = 
					filteredSecretMap.get(server);
				if (currentSecrets == null) {
					currentSecrets = new ArrayList<HTLCRevealSecret>();
				}
				currentSecrets.add(secret);
				filteredSecretMap.put(server, currentSecrets);
				//htlcIdToBuyerMap.remove(secret.getId());
			}
			
			for (HTLCBackOff backoff: allBackoffs) {
				HTLCHubBuyerServer server = htlcIdToBuyerMap.get(backoff.getId());
				List<HTLCBackOff> currentBackoffs = filteredBackOffMap.get(server);
				if (currentBackoffs == null) {
					currentBackoffs = new ArrayList<HTLCBackOff>();
				}
				currentBackoffs.add(backoff);
				filteredBackOffMap.put(server, currentBackoffs);
				//htlcIdToBuyerMap.remove(backoff.getId());
			}
			
			for (
				Map.Entry<HTLCHubBuyerServer, List<HTLCRevealSecret>> entry:
					filteredSecretMap.entrySet()
			) {				
				List<HTLCRevealSecret> secretsForBuyer = entry.getValue();
				HTLCServerUpdate.Builder updateForBuyer = 
					HTLCServerUpdate.newBuilder()	
						.addAllRevealSecrets(secretsForBuyer);
				if (filteredBackOffMap.get(entry.getKey()) != null) {
					updateForBuyer.addAllBackOffs(
						filteredBackOffMap.get(entry.getKey())
					);
					filteredBackOffMap.remove(entry.getKey());
				}
				TwoWayChannelMessage msgForBuyer = TwoWayChannelMessage.newBuilder()
					.setType(MessageType.HTLC_SERVER_UPDATE)
					.setHtlcServerUpdate(updateForBuyer.build())
					.build();
				
				// We can forward early
				log.info("Forwarding msg with secret reveal");
				entry.getKey().receiveMessage(msgForBuyer);
			}
			
			// Now see if there's anything left in the filteredBackoffMap
			for (
				Map.Entry<HTLCHubBuyerServer, List<HTLCBackOff>> entry:
				filteredBackOffMap.entrySet()
			) {
				List<HTLCBackOff> backOffForBuyer = entry.getValue();
				HTLCServerUpdate.Builder updateForBuyer = 
					HTLCServerUpdate.newBuilder()
						.addAllBackOffs(backOffForBuyer);
				TwoWayChannelMessage msgForBuyer = TwoWayChannelMessage.newBuilder()
					.setType(MessageType.HTLC_SERVER_UPDATE)
					.setHtlcServerUpdate(updateForBuyer.build())
					.build();
				log.info("Forwarding msg with secret reveal2");
				entry.getKey().receiveMessage(msgForBuyer);
			}
			log.info("LEFT MESSAGE FILTER/ no deadlock");
		}
	}
	
	public void registerDevice(String id, HTLCHubAndroidServer server) {
		log.info("Registering device {}", id);
		deviceIdToDeviceMap.put(id, server);
	}
	
	public void unregisterDevice(String deviceId) {
		deviceIdToDeviceMap.remove(deviceId);
	}
}
