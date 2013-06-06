package org.mdpnp.devices.serial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.mdpnp.messaging.Gateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDelegatingSerialDevice<T> extends AbstractSerialDevice {
	public AbstractDelegatingSerialDevice(Gateway gateway) {
		super(gateway);
	}
	public AbstractDelegatingSerialDevice(Gateway gateway, SerialSocket serialSocket) {
	    super(gateway, serialSocket);
    }
	private InputStream  inputStream;
	private OutputStream outputStream;
	private T delegate;
	
	protected synchronized void setOutputStream(OutputStream outputStream) {
		this.outputStream = outputStream;
		notifyAll();
	}
	
	protected synchronized void setInputStream(InputStream inputStream) {
		this.inputStream = inputStream;
		notifyAll();
	}
	private final Logger log = LoggerFactory.getLogger(AbstractDelegatingSerialDevice.class);
	
	protected abstract T buildDelegate(InputStream in, OutputStream out);
	protected abstract boolean delegateReceive(T delegate) throws IOException;
	
	@Override
	protected boolean doInitCommands(OutputStream outputStream) throws IOException {
	    log.trace("doInitCommands outputStream="+outputStream);
	    setOutputStream(outputStream);
	    return true;
	}
	
	protected synchronized T getDelegate() {
	    return getDelegate(true);
	}
	
	// just a failsafe
	private static final long MAX_GET_DELEGATE_WAIT_TIME = 20000L;
	
	protected synchronized T getDelegate(boolean build) {
	    long giveup = System.currentTimeMillis() + MAX_GET_DELEGATE_WAIT_TIME;
	    
		while(build && null == delegate && (inputStream == null || outputStream == null)) {
			try {
				log.trace("waiting, inputStream="+inputStream+", outputStream="+outputStream);
				long now = System.currentTimeMillis();
				if(now >= giveup) {
				    throw new IllegalStateException("Exceeded maximum time (" + MAX_GET_DELEGATE_WAIT_TIME + "ms awaiting calls to doInitCommands and process inputStream="+inputStream+" and outputStream=" + outputStream);
				} else {
				    wait(giveup - now);
				}
			} catch (InterruptedException e) {
			    log.error(e.getMessage(), e);
			}
		}
		if(build && null == delegate) {
			delegate = buildDelegate(inputStream, outputStream);
		}
		return delegate;
	}
	@Override
	protected void process(InputStream inputStream) throws IOException {
		log.trace("process inputStream="+inputStream);
		try {
			setInputStream(inputStream);
			final T delegate = getDelegate();
			boolean keepGoing = true;
			while(keepGoing) {
				keepGoing = delegateReceive(delegate);
			}
		} finally {
			this.inputStream = null;
			this.outputStream = null;
			this.delegate = null;
			log.trace("process ends");
		}
	}
}