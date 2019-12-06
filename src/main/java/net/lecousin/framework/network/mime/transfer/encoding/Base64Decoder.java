package net.lecousin.framework.network.mime.transfer.encoding;

import java.nio.ByteBuffer;

import net.lecousin.framework.io.encoding.Base64;

/** Decode base 64 content. */
public class Base64Decoder extends AbstractStepDecoder {
	
	/** Constructor. */
	public Base64Decoder(ContentDecoder next) {
		super(next);
	}

	private byte[] bufIn = new byte[4];
	private int inPos = 0;
	
	@Override
	protected ByteBuffer decodeStep(ByteBuffer data) throws Exception {
		byte[] decoded = new byte[data.remaining() * 3 / 4 + 3];
		int decodedPos = 0;
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
		return ByteBuffer.wrap(decoded, 0, decodedPos);
	}

}
