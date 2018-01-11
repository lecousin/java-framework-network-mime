package net.lecousin.framework.network.mime.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.LinkedIO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.util.Pair;

/** Interface for entities. */
public interface MimeEntity {

	/** Get a Readable containing this entity. */
	IO.Readable getReadableStream();
	
	/** Get the Content-Type header to set. */
	String getContentType();
	
	/** Get a list of additional headers, excluding the content type. */
	List<Pair<String, String>> getAdditionalHeaders();
	
	/** Create an IO.Readable with headers followed by the body. */
	@SuppressWarnings("resource")
	default IO.Readable createIOWithHeaders() {
		StringBuilder h = new StringBuilder(256);
		String s = getContentType();
		if (s != null)
			h.append("Content-Type: ").append(s).append("\r\n");
		for (Pair<String, String> p : getAdditionalHeaders())
			h.append(p.getValue1()).append(": ").append(p.getValue2()).append("\r\n");
		byte[] header = h.toString().getBytes(StandardCharsets.US_ASCII);
		return new LinkedIO.Readable.DeterminedSize("MIME", new IO.Readable[] {
			new ByteArrayIO(header, "MIME Headers"),
			getReadableStream()
		});
	}
	
	/** Get the list of values associated with the given header name. */
	default List<String> getHeaderValues(String name) {
		ArrayList<String> values = new ArrayList<>();
		for (Pair<String, String> h : getAdditionalHeaders())
			if (h.getValue1().equalsIgnoreCase(name))
				values.add(h.getValue2());
		return values;
	}
	
	/** Get the first value associated with the given header name, or null. */
	default String getHeaderSingleValue(String name) {
		for (Pair<String, String> h : getAdditionalHeaders())
			if (h.getValue1().equalsIgnoreCase(name))
				return h.getValue2();
		return null;
	}
	
}
