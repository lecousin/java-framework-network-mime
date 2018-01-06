package net.lecousin.framework.network.mime.entity;

import net.lecousin.framework.io.IO;

/** Abstract class for entities. */
public abstract class MimeEntity {

	/** Get a Readable containing this entity. */
	public abstract IO.Readable getReadableStream();
	
	/** Get the Content-Type header to set. */
	public abstract String getContentType();
	
}
