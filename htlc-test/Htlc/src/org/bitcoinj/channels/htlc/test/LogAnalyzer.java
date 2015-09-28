package org.bitcoinj.channels.htlc.test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogAnalyzer {
	
	private static final String MPC_ST = "MPC_st";
	private static final String MPC_EN = "MPC_en";
	private static final String SETT_ST = "SETT_st";
	private static final String SETT_EN = "SETT_en";
	
	private static final String SEL_ST = "SEL_st";
	private static final String SEL_EN = "SEL_en";
	private static final String BUY_ST = "BUY_st";
	private static final String BUY_EN = "BUY_en";
	private static final String STA_ST = "STAT_st";
	private static final String STA_EN = "STAT_en";
	private static final String STS_ST = "STAS_st";
	private static final String STS_EN = "STAS_en";
	
	private static final String HTLC_INIT1 = "HTLC_INIT1";
	private static final String HTLC_INIT2 = "HTLC_INIT2";
	private static final String HTLC_SETUP1 = "HTLC_SETUP1";
	private static final String HTLC_SETUP2 = "HTLC_SETUP2";
	private static final String HTLC_SET1 = "HTLC_SET1";
	private static final String HTLC_SET2 = "HTLC_SET2";
	private static final String HTLC_COMP1 = "HTLC_COMP1";
	private static final String HTLC_COMP2 = "HTLC_COMP2";
	
	private final Map<String, LogEntry> masterLog = new HashMap<>();
	
	class LogEntry {
		public Long selectStart;
		public Long selectEnd;
		public Long mpcStart;
		public Long mpcEnd;
		public Long settStart;
		public Long settEnd;
		public Long buyStart;
		public Long buyEnd;
		public Long statNStart;
		public Long statNEnd;
		public Long statSStart;
		public Long statSEnd;
		
		public Long htlcInit1;
		public Long htlcInit2;
		public Long htlcSetup1;
		public Long htlcSetup2;
		public Long htlcSet1;
		public Long htlcSet2;
		public Long htlcComp1;
		public Long htlcComp2;
	}
	
	public static void main(String[] args) {
		new LogAnalyzer().process("/home/frabu/buyer.log");
	}
	
	public void process(String fileName) {
		File file = new File(fileName);
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(file));
			String line = null;
			while ((line = br.readLine()) != null) {
				int i = -1;
				if ((i = getIndex(line)) > 0) {
					parseEntry(line, i);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		writeMasterLog("master");
	}
	
	private int getIndex(
		String line
	) {
		int i = -1;
		i = line.indexOf(MPC_ST); if (i > 0) return i;
		i = line.indexOf(MPC_EN); if (i > 0) return i;
		i = line.indexOf(SETT_ST); if (i > 0) return i;
		i = line.indexOf(SETT_EN); if (i > 0) return i;
		
		i = line.indexOf(SEL_ST); if (i > 0) return i;
		i = line.indexOf(SEL_EN); if (i > 0) return i;
		i = line.indexOf(BUY_ST); if (i > 0) return i;
		i = line.indexOf(BUY_EN); if (i > 0) return i;
		i = line.indexOf(STA_ST); if (i > 0) return i;
		i = line.indexOf(STA_EN); if (i > 0) return i;
		i = line.indexOf(STS_ST); if (i > 0) return i;
		i = line.indexOf(STS_EN); if (i > 0) return i;
		
		i = line.indexOf(HTLC_INIT1); if (i > 0) return i;
		i = line.indexOf(HTLC_INIT2); if (i > 0) return i;
		i = line.indexOf(HTLC_SETUP1); if (i > 0) return i;
		i = line.indexOf(HTLC_SETUP2); if (i > 0) return i;
		i = line.indexOf(HTLC_SET1); if (i > 0) return i;
		i = line.indexOf(HTLC_SET2); if (i > 0) return i;
		i = line.indexOf(HTLC_COMP1); if (i > 0) return i;
		i = line.indexOf(HTLC_COMP2);
		return i;
	}
	
	private void parseEntry(
		String line,
		int idx
	) {
		String entry = line.substring(idx);
		List<String> list = 
			new ArrayList<>(Arrays.asList(entry.split(" ")));

		String address = list.get(1);
		Long timestamp = Long.parseLong(list.get(2));
			
		LogEntry logEntry = masterLog.get(address);
		if (logEntry == null) {
			logEntry = new LogEntry();
		}
		
		String label = list.get(0);

		switch (label) {
			case MPC_ST:
				logEntry.mpcStart = timestamp;
				break;
			case MPC_EN:
				logEntry.mpcEnd = timestamp;
				break;
			case SETT_ST:
				logEntry.settStart = timestamp;
				break;
			case SETT_EN:
				logEntry.settEnd = timestamp;
				break;
			case SEL_ST:
				logEntry.selectStart = timestamp;
				break;
			case SEL_EN:
				logEntry.selectEnd = timestamp;
				break;
			case BUY_ST:
				logEntry.buyStart = timestamp;
				break;
			case BUY_EN:
				logEntry.buyEnd = timestamp;
				break;
			case STA_ST:
				logEntry.statNStart = timestamp;
				break;
			case STA_EN:
				logEntry.statNEnd = timestamp;
				break;
			case STS_ST:
				logEntry.statSStart = timestamp;
				break;
			case STS_EN:
				logEntry.statSEnd = timestamp;
				break;
			case HTLC_INIT1:
				logEntry.htlcInit1 = timestamp;
				break;
			case HTLC_INIT2:
				logEntry.htlcInit2 = timestamp;
				break;
			case HTLC_SETUP1:
				logEntry.htlcSetup1 = timestamp;
				break;
			case HTLC_SETUP2:
				logEntry.htlcSetup2 = timestamp;
				break;
			case HTLC_SET1:
				logEntry.htlcSet1 = timestamp;
				break;
			case HTLC_SET2:
				logEntry.htlcSet2 = timestamp;
				break;
			case HTLC_COMP1:
				logEntry.htlcComp1 = timestamp;
				break;
			case HTLC_COMP2:
				logEntry.htlcComp2 = timestamp;
				break;
		}
		
		masterLog.put(address, logEntry);
	}
	
	private void writeMasterLog(String outFile) {
		BufferedWriter bw_mpc = null;
		BufferedWriter bw_sel = null;
		BufferedWriter bw_buy = null;
		BufferedWriter bw_statn = null;
		BufferedWriter bw_stats = null;
		try {
			File mpc = new File(outFile + "_mpc.log");
			File sel = new File(outFile + "_sel.log");
			File buy = new File(outFile + "_buy.log");
			File statn = new File(outFile + "_statn.log");
			File stats = new File(outFile + "_stats.log");
			
			bw_mpc = new BufferedWriter(new FileWriter(mpc));
			bw_sel = new BufferedWriter(new FileWriter(sel));
			bw_buy = new BufferedWriter(new FileWriter(buy));
			bw_statn = new BufferedWriter(new FileWriter(statn));
			bw_stats = new BufferedWriter(new FileWriter(stats));
			// Write header
			bw_mpc.write(
				MPC_ST + "\t" +
				MPC_EN + "\t" + 
				SETT_ST + "\t" + 
				SETT_EN + "\t"
			);
			bw_mpc.newLine();
			bw_sel.write(
				SEL_ST + "\t" +
				SEL_EN + "\t"
			);
			bw_sel.newLine();
			bw_buy.write(
				BUY_ST + "\t" +
				BUY_EN + "\t" +
				HTLC_INIT1 + "\t" +
				HTLC_INIT2 + "\t" +
				HTLC_SETUP1 + "\t" +
				HTLC_SETUP2 + "\t" +
				HTLC_SET1 + "\t" +
				HTLC_SET2 + "\t" +
				HTLC_COMP1 + "\t" +
				HTLC_COMP2 + "\t"
			);
			bw_buy.newLine();
			bw_statn.write(
				STA_ST + "\t" +
				STA_EN + "\t"
			);
			bw_statn.newLine();
			bw_stats.write(
				STS_ST + "\t" +
				STS_EN + "\t"
			);
			bw_stats.newLine();
			
			int mpc_counter = 1;
			int sel_counter = 1;
			int statn_counter = 1;
			int stats_counter = 1;
			
			for (Map.Entry<String, LogEntry> entry: masterLog.entrySet()) {
				LogEntry row = entry.getValue();
				if (row.mpcStart != null && row.mpcEnd != null || 
					row.settStart != null && row.settEnd != null
				) {
					if (row.mpcEnd != null) {
						bw_mpc.write((mpc_counter++) + "\t" + (row.mpcEnd - row.mpcStart) + "\t \t \t");
					} 
					if (row.settEnd != null) {
						bw_mpc.write(" \t \t" + (row.settEnd - row.settStart) + "\t");
					}
					bw_mpc.newLine();
				} else if (
					row.buyStart != null && 
					row.buyEnd != null && 
					row.htlcInit1 != null &&
					row.htlcInit2 != null &&
					row.htlcSetup1 != null &&
					row.htlcSetup2 != null &&
					row.htlcSet1 != null &&
					row.htlcSet2 != null &&
					row.htlcComp1 != null &&
					row.htlcComp2 != null
				) {
					if (row.htlcComp2 - row.htlcSet2 < 800 && row.htlcSetup2 - row.htlcInit1 < 1800 && row.htlcSetup1 - row.htlcInit1 < 800) {
						bw_buy.write(row.buyStart + "\t" + row.buyEnd + "\t");
						bw_buy.write(row.htlcInit1 + "\t" + row.htlcInit2 + "\t");
						bw_buy.write(row.htlcSetup1 + "\t" + row.htlcSetup2 + "\t");
						bw_buy.write(row.htlcSet1 + "\t" + row.htlcSet2 + "\t");
						bw_buy.write(row.htlcComp1 + "\t" + row.htlcComp2 + "\t");
						bw_buy.newLine();
					}
				} else if (row.selectStart != null && row.selectEnd != null) {
					bw_sel.write((sel_counter++) + "\t" + (row.selectEnd - row.selectStart) + "\t");
					bw_sel.newLine();
				} else if (row.statNStart != null && row.statNEnd != null) {
					bw_statn.write((statn_counter++) + "\t" + (row.statNEnd - row.statNStart) + "\t");
					bw_statn.newLine();
				} else if (row.statSStart != null && row.statSEnd != null) {
					System.out.println("ROW: " + entry.getKey() + " " + (row.statSEnd - row.statSStart));
					bw_stats.write((stats_counter++) + "\t" + (row.statSEnd - row.statSStart) + "\t");
					bw_stats.newLine();
				}
			}
			
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (bw_mpc != null) {
				try {
					bw_mpc.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (bw_sel != null) {
				try {
					bw_sel.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (bw_statn != null) {
				try {
					bw_statn.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (bw_stats != null) {
				try {
					bw_stats.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (bw_buy != null) {
				try {
					bw_buy.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}