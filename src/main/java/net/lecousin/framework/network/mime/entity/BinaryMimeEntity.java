package net.lecousin.framework.network.mime.entity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.lecousin.framework.io.IO;
import net.lecousin.framework.util.Pair;

/**
 * Implementation of MimeEntity with a content type and a body.
 */
public class BinaryMimeEntity implements MimeEntity {

	/** Constructor. */
	public BinaryMimeEntity(String contentType, IO.Readable content) {
		this.contentType = contentType;
		this.content = content;
	}
	
	protected IO.Readable content;
	protected String contentType;
	protected List<Pair<String, String>> headers = new ArrayList<>();
	
	@Override
	public IO.Readable getReadableStream() {
		return content;
	}
	
	@Override
	public String getContentType() {
		return contentType;
	}
	
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
	
	@Override
	public List<Pair<String, String>> getAdditionalHeaders() {
		return headers;
	}
	
	/** Add a header. */
	public void addHeader(String name, String value) {
		headers.add(new Pair<>(name, value));
	}
	
	/** Set a header. */
	public void setHeader(String name, String value) {
		removeHeader(name);
		addHeader(name, value);
	}
	
	/** Remove a header. */
	public void removeHeader(String name) {
		for (Iterator<Pair<String, String>> it = headers.iterator(); it.hasNext(); ) {
			if (it.next().getValue1().equalsIgnoreCase(name))
				it.remove();
		}
	}
	
}
