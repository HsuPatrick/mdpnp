package org.mdpnp.apps.testapp;

import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collection;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.mdpnp.apps.gui.swing.DevicePanelFactory;
import org.mdpnp.comms.Gateway;
import org.mdpnp.comms.GatewayListener;
import org.mdpnp.comms.IdentifiableUpdate;
import org.mdpnp.comms.data.enumeration.EnumerationUpdate;
import org.mdpnp.comms.data.identifierarray.IdentifierArrayUpdate;
import org.mdpnp.comms.data.text.MutableTextUpdate;
import org.mdpnp.comms.data.text.MutableTextUpdateImpl;
import org.mdpnp.comms.data.text.TextUpdate;
import org.mdpnp.comms.nomenclature.Association;
import org.mdpnp.comms.nomenclature.ConnectedDevice;
import org.mdpnp.comms.nomenclature.Device;
import org.mdpnp.devices.cpc.bernoulli.DemoBernoulli;
import org.mdpnp.devices.draeger.medibus.DemoApollo;
import org.mdpnp.devices.draeger.medibus.DemoEvitaXL;
import org.mdpnp.devices.hospira.symbiq.DemoSymbiq;
import org.mdpnp.devices.masimo.radical.DemoRadical7;
import org.mdpnp.devices.nellcor.pulseox.DemoN595;
import org.mdpnp.devices.nonin.pulseox.DemoPulseOx;
import org.mdpnp.devices.oridion.capnostream.DemoCapnostream20;
import org.mdpnp.devices.philips.intellivue.DemoMP70;
import org.mdpnp.devices.simulation.SimulatedBloodPressureImpl;
import org.mdpnp.devices.simulation.pulseox.SimPulseOximeter;
import org.mdpnp.devices.webcam.WebcamImpl;
import org.mdpnp.transport.Adapter;
import org.mdpnp.transport.GetConnected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeviceAdapter {
	public enum Type {
	    PO_Simulator,
        NBP_Simulator,
        Bernoulli,
		Nonin,
		PhilipsMP70,
		DragerApollo,
		DragerEvitaXL,
		Capnostream20,
		NellcorN595,
	    MasimoRadical7,
		Webcam,
		Symbiq,
	} 
	
	private static JFrame frame;
	private static GetConnected getConnected;
	private static Collection<org.mdpnp.apps.gui.swing.DevicePanel> panels;
	
	private static boolean panelized;
	
	public static final Device buildDevice(Type type, Gateway deviceGateway) throws NoSuchFieldException, SecurityException, IOException {
		switch(type) {
		case Nonin:
			return new DemoPulseOx(deviceGateway);
		case NellcorN595:
			return new DemoN595(deviceGateway);
		case MasimoRadical7:
			return new DemoRadical7(deviceGateway);
		case PO_Simulator:
			return new SimPulseOximeter(deviceGateway);
		case NBP_Simulator:
			return new SimulatedBloodPressureImpl(deviceGateway);
		case PhilipsMP70:
			return new DemoMP70(deviceGateway);
		case DragerApollo:
			return new DemoApollo(deviceGateway);
		case DragerEvitaXL:
			return new DemoEvitaXL(deviceGateway);
		case Bernoulli:
			return new DemoBernoulli(deviceGateway);
		case Webcam:
			return new WebcamImpl(deviceGateway);
		case Capnostream20:
			return new DemoCapnostream20(deviceGateway);
		case Symbiq:
			return new DemoSymbiq(deviceGateway);
		default:
			throw new RuntimeException("Unknown type:"+type);
		}
	}
	
	private static synchronized void killAdapter() {
		if(adapter != null) {
			adapter.depart();
			adapter.tearDown();
			adapter = null;
		}
		if(getConnected!=null) {
			getConnected.disconnect();
			getConnected = null;
		}
	}
	
	private static final Logger log = LoggerFactory.getLogger(DeviceAdapter.class);
	private static Adapter adapter;
	
	public static void main(String[] args) throws Exception {
	    int domainId = 0;
	        
        try {
            domainId = Integer.parseInt(args[0]);
            args = Arrays.copyOfRange(args, 1, args.length);
            log.info("Using domainId="+domainId);
        } catch (Throwable t) {
            
        }
        
		boolean gui = true;
		Type type;
		
		final Gateway deviceGateway = new Gateway();
		final Gateway externalGateway = new Gateway();
		
		GatewayListener bootstrapListener = null;
		
		if(args.length > 0) {
			type = Type.valueOf(args[0]);
			gui = false;
			frame = null;
			
			deviceGateway.addListener(bootstrapListener = new GatewayListener() {

				@Override
				public void update(IdentifiableUpdate<?> update) {
					if(Device.GUID.equals(update.getIdentifier())) {
						log.debug("GUID:"+((TextUpdate)update).getValue());
					} else if(Device.NAME.equals(update.getIdentifier())) {
						log.debug("NAME:"+((TextUpdate)update).getValue());
					} else if(ConnectedDevice.STATE.equals(update.getIdentifier())) {
						log.debug("(Connection) STATE:"+((EnumerationUpdate)update).getValue());
					} else if(ConnectedDevice.CONNECTION_INFO.equals(update.getIdentifier())) {
						log.debug("CONNECTION_INFO:"+((TextUpdate)update).getValue());
					} else if(Association.ANNOUNCE_ARRIVE.equals(update.getIdentifier())) {
						log.debug("Announcing arrival...");
					} else if(Association.ACKNOWLEDGE_ARRIVE.equals(update.getIdentifier())) {
						log.debug("Arrival acknowledged");
					} else if(Association.ANNOUNCE_DEPART.equals(update.getIdentifier())) {
						log.debug("Announcing departure...");
					} else if(Association.ACKNOWLEDGE_DEPART.equals(update.getIdentifier())) {
						log.debug("Departure acknowledged");
					}
				}
				
			});
			final String[] _args = args;
			getConnected = new GetConnected(null, deviceGateway) {
				@Override
				protected void abortConnect() {
				}
				@Override
				protected String addressFromUser() {
					return _args.length > 1 ? _args[1] : null;
				}
				@Override
				protected String addressFromUserList(String[] list) {
					return _args.length > 1 ? _args[1] : null;
				}
				@Override
				protected boolean isFixedAddress() {
					return true;
				}
			};
		} else {
			// Add a listener that will build GUI components
			deviceGateway.addListener(bootstrapListener = new GatewayListener() {
				
				@Override
				public void update(IdentifiableUpdate<?> update) {
					if(!panelized && Device.GET_AVAILABLE_IDENTIFIERS.equals(update.getIdentifier())) {
						panelized = true;
						// find the appropriate GUI representations for this device
						panels = DevicePanelFactory.findPanel((IdentifierArrayUpdate)update, deviceGateway, update.getSource());
						
						try {
							SwingUtilities.invokeAndWait(new Runnable() {
								public void run() {
									frame.getContentPane().removeAll();
									frame.getContentPane().setLayout(new GridLayout(panels.size(), 1));
									for(org.mdpnp.apps.gui.swing.DevicePanel panel : panels) {
										frame.getContentPane().add(panel);
									}
									frame.getContentPane().validate();
								}
							});
						} catch (InvocationTargetException e) {
							e.printStackTrace();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
				
			});
			frame = new JFrame("Adapter") {
				public void dispose() {
					killAdapter();
				};
			};
			frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
			frame.setLocationRelativeTo(null);
			frame.setSize(320, 480);
			frame.setVisible(true);

			getConnected = new GetConnected(frame, deviceGateway);
			
			if(null == (type = (Type) JOptionPane.showInputDialog(frame, "Choose a device to adapt", "Device", JOptionPane.QUESTION_MESSAGE, null, Type.values(), null))) {
				frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
				return;
			}
		}
		
		
		
		Device device = buildDevice(type, deviceGateway);

			
		
		adapter = new Adapter(domainId, deviceGateway, externalGateway);
		
		if(gui) {
			getConnected.connect();
		
			frame.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent e) {
					killAdapter();
				}
			});
		
			
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				public void run() {
					killAdapter();
				}
			}));
			MutableTextUpdate tu = new MutableTextUpdateImpl(Device.REQUEST_AVAILABLE_IDENTIFIERS);
			tu.setTarget(adapter.getSource());
			// This request will drive GUI creation
			deviceGateway.update(bootstrapListener, tu);
			
		} else {
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				public void run() {
					killAdapter();
				}
			}));
			System.err.println("Type quit<enter> to exit");

			getConnected.connect();

			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
			String line = null;
			while(!"quit".equals(line)) {
				line = br.readLine();
			}
		}
		
		
		
		
		
		
	}
	
}