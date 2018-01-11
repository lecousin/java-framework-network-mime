package net.lecousin.framework.network.mime.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.mime.MIME;
import net.lecousin.framework.util.Pair;

// skip checkstyle: AbbreviationAsWordInName
/**
 * Generic implementation of MimeEntity, simply holding a {@link MIME} object.
 */
public class GenericMimeEntity implements MimeEntity {

	/** Constructor. */
	public GenericMimeEntity(MIME mime) {
		this.mime = mime;
	}
	
	protected MIME mime;
	
	@Override
	public IO.Readable getReadableStream() {
		IO.Readable body = mime.getBodyInput();
		if (body != null) return body;
		body = mime.getBodyOutputAsInput();
		return body;
	}
	
	@Override
	public String getContentType() {
		return mime.getContentType();
	}
	
	@Override
	public List<Pair<String, String>> getAdditionalHeaders() {
		ArrayList<Pair<String, String>> headers = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : mime.getHeaders().entrySet()) {
			for (String value : e.getValue())
				headers.add(new Pair<>(e.getKey(), value));
		}
		return headers;
	}
	
	/** Return the MIME object. */
	public MIME getMIME() {
		return mime;
	}
	
}
