package net.lecousin.framework.network.mime.transfer.encoding;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.encoding.Base64;

/** Decode base 64 content. */
public class Base64Decoder implements ContentDecoder {
	
	/** Constructor. */
	public Base64Decoder(ContentDecoder next) {
		this.next = next;
	}

	private ContentDecoder next;
	private byte[] bufIn = new byte[4];
	private int inPos = 0;
	private Async<IOException> lastDecode = null;
	
	@Override
	public IAsync<IOException> decode(ByteBuffer data) {
		Async<IOException> decode = new Async<>();
		Task.Cpu<Void, IOException> task = new Task.Cpu<Void, IOException>("Decoding Base64 data", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() throws IOException {
				byte[] decoded = new byte[data.remaining() * 3 / 4 + 3];
				int decodedPos = 0;
				try {
					while (data.hasRemaining()) {
						int l = 4 - inPos;
						if (l > data.remaining()) l = data.remaining();
						data.get(bufIn, inPos, l);
						inPos += l;
						if (inPos == 4) {
							int nb = Base64.decode4BytesBase64(bufIn, decoded, decodedPos);
							decodedPos += nb;
							inPos = 0;
						}
					}
				} catch (Exception e) {
					throw IO.error(e);
				}
				IAsync<IOException> write = next.decode(ByteBuffer.wrap(decoded, 0, decodedPos));
				write.onDone(() -> {
					if (write.hasError()) decode.error(write.getError());
					else if (write.isCancelled()) decode.cancel(write.getCancelEvent());
					else decode.unblock();
				});
				return null;
			}
		};
		if (lastDecode == null) {
			lastDecode = decode;
			task.start();
			return decode;
		}
		Async<IOException> previous = lastDecode;
		lastDecode = decode;
		previous.onDone(() -> {
			if (previous.hasError()) decode.error(decode.getError());
			else if (previous.isCancelled()) decode.cancel(decode.getCancelEvent());
			else task.start();
		});
		return decode;
	}
	
	@Override
	public IAsync<IOException> endOfData() {
		return next.endOfData();
	}

}
