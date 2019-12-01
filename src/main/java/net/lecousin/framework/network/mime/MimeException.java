package net.lecousin.framework.network.mime;

/** Error encoding or decoding a MIME message. */
public class MimeException extends Exception {

	private static final long serialVersionUID = 1L;

	/** COnstructor. */
	public MimeException(String message) {
		super(message);
	}

	/** COnstructor. */
	public MimeException(String message, Throwable cause) {
		super(message, cause);
	}
	
}
