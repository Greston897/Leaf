package org.dreeam.leaf.perf.ram;

/**
 * Leaf - one participant of the RAM janitor.
 * <p>
 * A {@code Trimmable} is a <b>cache</b>, never an owner: everything it holds can be rebuilt, so dropping
 * part of it costs some work later and nothing else. That property is what makes an automatic cleaner
 * possible at all - a server that starts evicting data it cannot rebuild is a server that breaks under
 * memory pressure, which is exactly the opposite of the point.
 * <p>
 * The janitor asks three questions and never touches the internals of the implementation:
 * <ul>
 *     <li>{@link #retainedBytes()} - how much of the heap (or of native memory, for the off-heap payload
 *     cache) this cache is holding right now,</li>
 *     <li>{@link #hits()} - a monotonic counter of useful lookups, which the janitor turns into a
 *     <i>recent</i> hit rate between two passes; a cache that is earning its keep is trimmed last,</li>
 *     <li>{@link #trim(double, boolean)} - shed roughly the requested share of the cache.</li>
 * </ul>
 * Implementations must be safe to call from the server thread at any point between two ticks and must
 * never throw: a failure inside a cache must not abort a janitor pass.
 */
public interface Trimmable {

    /**
     * @return a short, stable identifier used in reports and in the {@code /leaf ram} table
     */
    String id();

    /**
     * @return an estimate of what this cache is holding, in bytes. Estimates are fine - the janitor only
     * compares them and sums them up - but they should be monotonic with the real size.
     */
    long retainedBytes();

    /**
     * @return the number of entries currently held, used to weigh the recent hit rate
     */
    int size();

    /**
     * @return a monotonically increasing counter of successful lookups
     */
    long hits();

    /**
     * Sheds approximately {@code ratio} of the cache, oldest / least recently used entries first.
     *
     * @param ratio  a value in {@code (0, 1]}; {@code 1.0} means "drop everything that is not in use"
     * @param dryRun when {@code true} nothing may be dropped, only the effect has to be estimated
     * @return an estimate of the bytes that were (or would be) released
     */
    long trim(double ratio, boolean dryRun);

    /**
     * @return {@code true} when this cache should only be trimmed above the emergency watermark, because
     * dropping it would cost more than the memory it returns
     */
    default boolean expensiveToRefill() {
        return false;
    }
}
