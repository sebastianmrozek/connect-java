package org.glassfish.jersey.logging;

import cd.connect.context.ConnectContext;
import cd.connect.spring.jersey.JerseyLoggerPoint;
import cd.connect.spring.jersey.log.JerseyFiltering;
import org.glassfish.jersey.message.MessageUtils;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base for both client and server jersey tracing.
 */
abstract class BaseFilteringLogger implements WriterInterceptor {

	@SuppressWarnings("NonConstantLogger")
	final org.slf4j.Logger logger = JerseyLoggerPoint.logger;
	final AtomicLong _id = new AtomicLong(0);
	final LoggingFeature.Verbosity verbosity;
	final int maxEntitySize;

	protected final JerseyFiltering jerseyFiltering;

	/**
	 * Creates a logging filter with custom logger and entity logging turned on, but potentially limiting the size
	 * of entity to be buffered and logged.
	 *
	 * @param verbosity     verbosity of the logged messages.
	 * @param maxEntitySize maximum number of entity bytes to be logged (and buffered) - if the entity is larger,
	 *                      logging filter will print (and buffer in memory) only the specified number of bytes
	 */
	BaseFilteringLogger(JerseyFiltering jerseyFiltering, LoggingFeature.Verbosity verbosity, int maxEntitySize) {
		this.verbosity = verbosity;
		this.maxEntitySize = Math.max(0, maxEntitySize);;
		this.jerseyFiltering = jerseyFiltering;
	}


	/**
	 * Prefix will be printed before requests
	 */
	static final String REQUEST_PREFIX = "> ";
	/**
	 * Prefix will be printed before response
	 */
	static final String RESPONSE_PREFIX = "< ";
	/**
	 * The entity stream property
	 */
	static final String ENTITY_LOGGER_PROPERTY = LoggingFeature.class.getName() + ".entityLogger";
	/**
	 * Logging record id property
	 */
	static final String LOGGING_ID_PROPERTY = LoggingFeature.class.getName() + ".id";
	private static final String NOTIFICATION_PREFIX = "* ";
	private static final MediaType TEXT_MEDIA_TYPE = new MediaType("text", "*");

	private static final Set<MediaType> READABLE_APP_MEDIA_TYPES = new HashSet<MediaType>() {{
		add(TEXT_MEDIA_TYPE);
		add(MediaType.APPLICATION_ATOM_XML_TYPE);
		add(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
		add(MediaType.APPLICATION_JSON_TYPE);
		add(MediaType.APPLICATION_SVG_XML_TYPE);
		add(MediaType.APPLICATION_XHTML_XML_TYPE);
		add(MediaType.APPLICATION_XML_TYPE);
	}};

	private static final Comparator<Map.Entry<String, List<String>>> COMPARATOR =
		new Comparator<Map.Entry<String, List<String>>>() {

			@Override
			public int compare(final Map.Entry<String, List<String>> o1, final Map.Entry<String, List<String>> o2) {
				return o1.getKey().compareToIgnoreCase(o2.getKey());
			}
		};

	/**
	 * Logs a {@link StringBuilder} parameter at required level.
	 *
	 * @param b message to log
	 */
	void log(final StringBuilder b) {
		if (logger != null && logger.isTraceEnabled()) {
			logger.trace("{}", b.toString());
		}


		ConnectContext.remove(Constants.REST_CONTEXT);
		ConnectContext.remove(Constants.REST_STATUS_CODE);
		ConnectContext.remove(Constants.REST_TIMING);
	}

	private StringBuilder prefixId(final StringBuilder b, final long id) {
		b.append(Long.toString(id)).append(" ");
		return b;
	}

	void printRequestLine(final StringBuilder b, final String note, final long id, final String method, final URI uri) {
		prefixId(b, id).append(NOTIFICATION_PREFIX)
			.append(note)
			.append(" on thread ").append(Thread.currentThread().getName())
			.append("\n");
		prefixId(b, id).append(REQUEST_PREFIX).append(method).append(" ")
			.append(uri.toASCIIString()).append("\n");
	}

	void printResponseLine(final StringBuilder b, final String note, final long id, final int status) {
		prefixId(b, id).append(NOTIFICATION_PREFIX)
			.append(note)
			.append(" on thread ").append(Thread.currentThread().getName()).append("\n");
		prefixId(b, id).append(RESPONSE_PREFIX)
			.append(Integer.toString(status))
			.append("\n");
	}

	void printPrefixedHeaders(final StringBuilder b,
	                          final long id,
	                          final String prefix,
	                          final MultivaluedMap<String, String> headers) {
		for (final Map.Entry<String, List<String>> headerEntry : getSortedHeaders(headers.entrySet())) {
			final List<?> val = headerEntry.getValue();
			final String header = headerEntry.getKey();

			if (val.size() == 1) {
				prefixId(b, id).append(prefix).append(header).append(": ").append(val.get(0)).append("\n");
			} else {
				final StringBuilder sb = new StringBuilder();
				boolean add = false;
				for (final Object s : val) {
					if (add) {
						sb.append(',');
					}
					add = true;
					sb.append(s);
				}
				prefixId(b, id).append(prefix).append(header).append(": ").append(sb.toString()).append("\n");
			}
		}
	}

	Set<Map.Entry<String, List<String>>> getSortedHeaders(final Set<Map.Entry<String, List<String>>> headers) {
		final TreeSet<Map.Entry<String, List<String>>> sortedHeaders = new TreeSet<Map.Entry<String, List<String>>>(COMPARATOR);
		sortedHeaders.addAll(headers);
		return sortedHeaders;
	}

	InputStream logInboundEntity(final StringBuilder b, InputStream stream, final Charset charset) throws IOException {
		if (!stream.markSupported()) {
			stream = new BufferedInputStream(stream);
		}
		stream.mark(maxEntitySize + 1);
		final byte[] entity = new byte[maxEntitySize + 1];
		final int entitySize = stream.read(entity);
		b.append(new String(entity, 0, Math.min(entitySize, maxEntitySize), charset));
		if (entitySize > maxEntitySize) {
			b.append("...more...");
		}
		b.append('\n');
		stream.reset();
		return stream;
	}

	@Override
	public void aroundWriteTo(final WriterInterceptorContext writerInterceptorContext)
		throws IOException, WebApplicationException {
		final LoggingStream stream = (LoggingStream) writerInterceptorContext.getProperty(ENTITY_LOGGER_PROPERTY);
		writerInterceptorContext.proceed();
		if (logger.isTraceEnabled() && printEntity(verbosity, writerInterceptorContext.getMediaType())) {
			if (stream != null) {
				log(stream.getStringBuilder(MessageUtils.getCharset(writerInterceptorContext.getMediaType())));
			}
		}
	}

	/**
	 * Returns {@code true} if specified {@link MediaType} is considered textual.
	 * <p>
	 * See {@link #READABLE_APP_MEDIA_TYPES}.
	 *
	 * @param mediaType the media type of the entity
	 * @return {@code true} if specified {@link MediaType} is considered textual.
	 */
	static boolean isReadable(MediaType mediaType) {
		if (mediaType != null) {
			for (MediaType readableMediaType : READABLE_APP_MEDIA_TYPES) {
				if (readableMediaType.isCompatible(mediaType)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns {@code true} if entity has to be printed.
	 *
	 * @param verbosity the configured verbosity .
	 * @param mediaType the media type of the payload.
	 * @return {@code true} if entity has to be printed.
	 */
	static boolean printEntity(LoggingFeature.Verbosity verbosity, MediaType mediaType) {
		return verbosity == LoggingFeature.Verbosity.PAYLOAD_ANY || (verbosity == LoggingFeature.Verbosity.PAYLOAD_TEXT && isReadable(mediaType));
	}

	/**
	 * Helper class used to log an entity to the output stream up to the specified maximum number of bytes.
	 */
	class LoggingStream extends FilterOutputStream {

		private final StringBuilder b;
		private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

		/**
		 * Creates {@code LoggingStream} with the entity and the underlying output stream as parameters.
		 *
		 * @param b     contains the entity to log.
		 * @param inner the underlying output stream.
		 */
		LoggingStream(final StringBuilder b, final OutputStream inner) {
			super(inner);

			this.b = b;
		}

		StringBuilder getStringBuilder(final Charset charset) {
			// write entity to the builder
			final byte[] entity = baos.toByteArray();

			b.append(new String(entity, 0, Math.min(entity.length, maxEntitySize), charset));
			if (entity.length > maxEntitySize) {
				b.append("...more...");
			}
			b.append('\n');

			return b;
		}

		@Override
		public void write(final int i) throws IOException {
			if (baos.size() <= maxEntitySize) {
				baos.write(i);
			}
			out.write(i);
		}
	}
}
