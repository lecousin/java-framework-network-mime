package net.lecousin.framework.network.mime.transfer.encoding;

import java.nio.ByteBuffer;

import net.lecousin.framework.io.encoding.QuotedPrintable;

/**
 * Quoted printable transfer (Transfer-Encoding: quoted-printable).
 * A Content-Length must be specified.
 */
public class QuotedPrintableDecoder extends AbstractStepDecoder {

	/** Constructor. */
	public QuotedPrintableDecoder(ContentDecoder next) {
		super(next);
	}
	
	private byte[] previousRemainingData = null;

	@Override
	protected ByteBuffer decodeStep(ByteBuffer data) throws Exception {
		ByteBuffer input;
		if (previousRemainingData != null) {
			byte[] b = new byte[previousRemainingData.length + data.remaining()];
			System.arraycopy(previousRemainingData, 0, b, 0, previousRemainingData.length);
			data.get(b, previousRemainingData.length, b.length - previousRemainingData.length);
			input = ByteBuffer.wrap(b);
		} else {
			input = data;
		}
		ByteBuffer decoded = QuotedPrintable.decode(input);
		if (input.hasRemaining()) {
			previousRemainingData = new byte[input.remaining()];
			input.get(previousRemainingData, 0, input.remaining());
		} else {
			previousRemainingData = null;
		}
		return decoded;
	}

}
