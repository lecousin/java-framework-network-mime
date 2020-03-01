package net.lecousin.framework.network.mime.header;

/** Interface for a class wrapping a MimeHeaders, adding methods to ease usage.
 * @param <ME> type of implementing class, to be returned by methods
 */
public interface MimeHeadersContainer<ME extends MimeHeadersContainer<ME>> {

	/** Return the MIME headers. */
	MimeHeaders getHeaders();
	
	/** Set a MIME header. */
	@SuppressWarnings("unchecked")
	default ME setHeader(String name, String rawValue) {
		getHeaders().setRawValue(name, rawValue);
		return (ME)this;
	}
	
	/** Set a MIME header. */
	@SuppressWarnings("unchecked")
	default ME setHeader(String name, HeaderValueFormat value) {
		getHeaders().set(name, value);
		return (ME)this;
	}
	
	/** Set a MIME header. */
	@SuppressWarnings("unchecked")
	default ME setHeader(MimeHeader header) {
		getHeaders().set(header);
		return (ME)this;
	}
	
	/** Add a MIME header. */
	@SuppressWarnings("unchecked")
	default ME addHeader(String name, String rawValue) {
		getHeaders().addRawValue(name, rawValue);
		return (ME)this;
	}
	
	/** Add a MIME header. */
	@SuppressWarnings("unchecked")
	default ME addHeader(String name, HeaderValueFormat value) {
		getHeaders().add(name, value);
		return (ME)this;
	}
	
	/** Add a MIME header. */
	@SuppressWarnings("unchecked")
	default ME addHeader(MimeHeader header) {
		getHeaders().add(header);
		return (ME)this;
	}
	
}
