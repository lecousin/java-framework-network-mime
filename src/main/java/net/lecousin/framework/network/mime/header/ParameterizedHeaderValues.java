package net.lecousin.framework.network.mime.header;

/**
 * Comma separated list of parameterized header values.
 */
public class ParameterizedHeaderValues extends HeaderValues<ParameterizedHeaderValue> {

	/** Return the value having the given main value. */
	public ParameterizedHeaderValue getMainValue(String value) {
		for (ParameterizedHeaderValue v : getValues())
			if (value.equals(v.getMainValue()))
				return v;
		return null;
	}
	
	/** Return true if a value has the given main value. */
	public boolean hasMainValue(String value) {
		return getMainValue(value) != null;
	}
	
	@Override
	protected ParameterizedHeaderValue newValue() {
		return new ParameterizedHeaderValue();
	}
	
}
