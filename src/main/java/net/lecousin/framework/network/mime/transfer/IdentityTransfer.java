package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.mime.header.MimeHeaders;

/**
 * Default transfer, using Content-Length to know how much bytes need to be read, and read it.
 */
public final class IdentityTransfer {
	
	private IdentityTransfer() { /* no instance */ }
	
	public static final String TRANSFER_NAME = "identity";
	
	/** Identity transfer receiver. */
	public static class Receiver implements PartialAsyncConsumer<ByteBuffer, IOException> {

		/** Constructor. */
		public Receiver(MimeHeaders headers, AsyncConsumer<ByteBuffer, IOException> consumer) throws IOException {
			this.consumer = consumer;
			Long s = headers.getContentLength();
			if (s == null)
				throw new IOException("No content length for identity transfer: impossible to transfer data");
			size = s.longValue();
			eot = (size == 0);
			logger = LCCore.getApplication().getLoggerFactory().getLogger(IdentityTransfer.class);
		}
		
		private long pos = 0;
		private long size;
		private boolean eot;
		private AsyncConsumer<ByteBuffer, IOException> consumer;
		private Logger logger;
	
		@Override
		public boolean isExpectingData() {
			return size > 0;
		}
	
		@Override
		public AsyncSupplier<Boolean, IOException> consume(ByteBuffer buf) {
			AsyncSupplier<Boolean, IOException> result = new AsyncSupplier<>();
			if (eot) {
				if (logger.trace())
					logger.trace("End of identity transfer");
				consumer.end().onDone(() -> result.unblockSuccess(Boolean.TRUE), result);
				return result;
			}
			int l = buf.remaining();
			ByteBuffer subBuffer;
			if (pos + l > size) {
				int l2 = (int)(size - pos);
				subBuffer = buf.duplicate();
				subBuffer.limit(buf.limit() - (l - l2));
				l = l2;
			} else {
				subBuffer = buf.duplicate();
			}
			if (logger.trace())
				logger.trace("Received bytes: " + l + " at " + pos + "/" + size);
			pos += l;
			buf.position(buf.position() + l);
			eot = pos == size;
			IAsync<IOException> decode = consumer.consume(subBuffer.asReadOnlyBuffer());
			decode.onDone(() -> {
				if (decode.isSuccessful()) {
					if (!eot) {
						result.unblockSuccess(Boolean.FALSE);
					} else {
						if (logger.trace())
							logger.trace("End of identity transfer");
						consumer.end().onDone(() -> result.unblockSuccess(Boolean.TRUE), result);
					}
				} else if (decode.hasError()) {
					result.unblockError(IO.error(decode.getError()));
				} else {
					result.cancel(decode.getCancelEvent());
				}
			});
			return result;
		}
	}

}
