package net.lecousin.framework.network.mime.entity;

import net.lecousin.framework.io.IO;

public class BinaryMimeEntity extends MimeEntity {

	public BinaryMimeEntity(String contentType, IO.Readable content) {
		this.contentType = contentType;
		this.content = content;
	}
	
	protected IO.Readable content;
	protected String contentType;
	
	@Override
	public IO.Readable getReadableStream() {
		return content;
	}
	
	@Override
	public String getContentType() {
		return contentType;
	}
	
}
