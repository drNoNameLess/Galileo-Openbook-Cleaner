package de.scrum_master.galileo.filter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import de.scrum_master.galileo.Book;

public class FilterChain implements Runnable {
	private Book book;
	private File origFile;
	private InputStream source;
	private OutputStream target;
	private boolean runMultiThreaded;
	private Queue<Class<? extends BasicFilter>> filters;

	public FilterChain(
		Book book, File origFile,
		InputStream source, OutputStream target,
		boolean runMultiThreaded,
		Queue<Class<? extends BasicFilter>> filters
	) {
		this.book = book;
		this.origFile = origFile;
		this.source = source;
		this.target = target;
		this.runMultiThreaded = runMultiThreaded;
		this.filters = filters;
	}

	public FilterChain(
		Book book, File origFile,
		File source, File target,
		boolean runMultiThreaded,
		Queue<Class<? extends BasicFilter>> filters
	) throws FileNotFoundException {
		this(
			book, origFile,
			new FileInputStream(source), new FileOutputStream(target),
			runMultiThreaded,
			filters
		);
	}

	/**
	 * This method actually performs the conversion/filtering steps defined by the {@code FilterChain}.
	 * At the same time it is a reflection-based factory method for instantiating {@link BasicFilter} objects.
	 * <p>
	 * <b>Please note:</b> This method expects each concrete {@code BasicFilter} subclass to provide
	 * <ul>
	 *   <li>a constructor with signature {@code MyClass(InputStream.class, OutputStream.class, File.class)}
	 *   <li>a static member {@code String MyClass.FILE_EXTENSION} returning a default file extension
	 *       like ".ext" for this type of filter
	 * </ul>
	 * This is not really elegant because it circumvents compile-time type checking, but it works.
	 * BTW: What we would really need here are abstract static methods, but they do not exist in Java.
	 */
	public void run() {
		List<BasicFilter> filterInstances = new LinkedList<>();
		Class<? extends BasicFilter> filterClass;
		InputStream in;
		OutputStream out = null;
		File outFile = null;
		try {
			while ((filterClass = filters.poll()) != null) {
				// Initialise filter input stream
				if (out == null) {
					// Special case: head of chain
					in = source;
				}
				else if (runMultiThreaded) {
					// Connect pipe input to output of previous pipe
					in = new PipedInputStream((PipedOutputStream) out);
				}
				else {
					// Read from file created by previous filter
					in = new FileInputStream(outFile);
				}

				// Initialise filter output stream
				if (filters.isEmpty()) {
					// Special case: end of chain
					out = target;
				}
				else if (runMultiThreaded) {
					out = new PipedOutputStream();
				}
				else {
					// TODO: What if there are two filters of the same class and one of them is *not* the first
					// or last one in the chain? We would have a name collision (same file extension).
					String fileExtension = (String) filterClass.getDeclaredField("FILE_EXTENSION").get(null);
					outFile = new File(origFile + fileExtension);
					out = new FileOutputStream(outFile);
				}

				// Instantiate filter and add it to list for running it later
				BasicFilter filter = filterClass
					.getConstructor(InputStream.class, OutputStream.class, Book.class, File.class)
					.newInstance(in, out, book, origFile);
				filterInstances.add(filter);
			}
			for (BasicFilter filter : filterInstances) {
				// Run filter, either in-thread or as a separate thread.
				//
				// NOTE: It is essential to run filters *after* they have been set up completely because
				// otherwise there might be pipe buffer overflows if filter (n) is already writing into its pipe
				// before filter (n+1) has connected its reading end to it.
				if (runMultiThreaded)
					new Thread(filter).start();
				else
					filter.run();
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
