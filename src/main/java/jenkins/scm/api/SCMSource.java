/*
 * The MIT License
 *
 * Copyright (c) 2011-2013, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.scm.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.util.AlternativeUiTextProvider;
import hudson.util.LogTaskListener;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;

/**
 * A {@link SCMSource} is responsible for fetching {@link SCMHead} and corresponding {@link SCMRevision} instances from
 * which it can build {@link SCM} instances that are configured to check out the specific {@link SCMHead} at the
 * specified {@link SCMRevision}.
 *
 * Each {@link SCMSource} is owned by a {@link SCMSourceOwner}, if you need to find all the owners use
 * {@link SCMSourceOwners#all()} to iterate through them, e.g. to notify {@link SCMSource} instances of push
 * notification from the server they source {@link SCMHead}s from.
 *
 * <strong>NOTE:</strong> This layer does not cache remote calls but can cache intermediary results. For example,
 * with Subversion it is acceptable to cache the last revisions of various directory entries to minimize network
 * round trips, but any of the calls to {@link #fetch(TaskListener)},
 * {@link #fetch(SCMHeadObserver, hudson.model.TaskListener)} or
 * {@link #fetch(SCMHead, hudson.model.TaskListener)} must
 * involve at least one network round trip to validate any cached information.
 */
public abstract class SCMSource extends AbstractDescribableImpl<SCMSource>
        implements ExtensionPoint {

    /**
     * Replaceable pronoun of that points to a {@link SCMSource}. Defaults to {@code null} depending on the context.
     * @since FIXME
     */
    public static final AlternativeUiTextProvider.Message<SCMSource> PRONOUN
            = new AlternativeUiTextProvider.Message<SCMSource>();
    /**
     * This thread local allows us to refactor the {@link SCMSource} API so that there are now implementations that
     * explicitly pass the {@link SCMSourceCriteria} while legacy implementations can still continue to work
     * without having to be rewritten.
     *
     * @since FIXME
     */
    private static final ThreadLocal<SCMSourceCriteria> compatibilityHack = new ThreadLocal<SCMSourceCriteria>();
    /**
     * A special marker value used by {@link #getCriteria()} and stored in {@link #compatibilityHack} to signal
     * that {@link #getCriteria()} should return {@code null}.
     *
     * @since FIXME
     */
    private static final SCMSourceCriteria nullSCMSourceCriteria = new SCMSourceCriteria() {
        @Override
        public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
            return true;
        }
    };

    /**
     * The ID of this source.
     */
    @CheckForNull
    @GuardedBy("this")
    private String id;

    /**
     * Constructor.
     *
     * @param id the id or {@code null}.
     */
    protected SCMSource(@CheckForNull String id) {
        this.id = id == null ? UUID.randomUUID().toString() : id;
    }

    /**
     * The ID of this source. The ID is not related to anything at all.
     *
     * @return the ID of this source.
     */
    @NonNull
    public final synchronized String getId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    /**
     * The owner of this source, used as a context for looking up things such as credentials.
     */
    @GuardedBy("this")
    @CheckForNull
    private transient SCMSourceOwner owner;

    /**
     * Sets the owner.
     *
     * @param owner the owner.
     */
    public final synchronized void setOwner(@CheckForNull SCMSourceOwner owner) {
        this.owner = owner;
    }

    /**
     * Gets the owner.
     *
     * @return the owner.
     */
    @CheckForNull
    public final synchronized SCMSourceOwner getOwner() {
        return owner;
    }

    /**
     * Returns the branch criteria.
     *
     * @return the branch criteria.
     */
    @CheckForNull
    protected final SCMSourceCriteria getCriteria() {
        SCMSourceCriteria hack = compatibilityHack.get();
        if (hack != null) {
            return hack == nullSCMSourceCriteria ? null : hack;
        }
        final SCMSourceOwner owner = getOwner();
        if (owner == null) {
            return null;
        }
        return owner.getSCMSourceCriteria(this);
    }

    /**
     * Fetches the latest heads and corresponding revisions. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     *
     * @param <O> Observer type.
     * @param observer an optional observer of interim results.
     * @param listener the task listener
     * @return the provided observer.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @NonNull
    public final <O extends SCMHeadObserver> O fetch(@NonNull O observer,
                                                        @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        _retrieve(getCriteria(), observer, defaultListener(listener));
        return observer;
    }

    /**
     * Fetches the latest heads and corresponding revisions. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     *
     * @param <O> Observer type.
     * @param criteria the criteria to use.
     * @param observer an optional observer of interim results.
     * @param listener the task listener
     * @return the provided observer.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @NonNull
    public final <O extends SCMHeadObserver> O fetch(@CheckForNull SCMSourceCriteria criteria, @NonNull O observer,
                                                     @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        _retrieve(criteria, observer, defaultListener(listener));
        return observer;
    }

    /**
     * Fetches the latest heads and corresponding revisions scoped against a specific event.
     * Implementers are free to cache intermediary results but the call must always check the validity of any
     * intermediary caches. Where the event {@link SCMEvent#isPayloadTrusted()}, implementations are free to use
     * the event information to check the validity of any intermediary caches..
     *
     * @param <O> Observer type.
     * @param criteria the criteria to use.
     * @param observer an optional observer of interim results.
     * @param event the event from which the fetch should be scoped.
     * @param listener the task listener
     * @return the provided observer.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @see SCMEvent#isPayloadTrusted()
     * @since FIXME
     */
    @NonNull
    public final <O extends SCMHeadObserver> O fetch(@CheckForNull SCMSourceCriteria criteria,
                                                     @NonNull O observer, @NonNull SCMHeadEvent<?> event,
                                                     @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        retrieve(criteria, observer, event, defaultListener(listener));
        return observer;
    }

    /**
     * Fetches the latest heads and corresponding revisions scoped against a specific event.
     * Implementers are free to cache intermediary results but the call must always check the validity of any
     * intermediary caches. Where the event {@link SCMEvent#isPayloadTrusted()}, implementations are free to use
     * the event information to check the validity of any intermediary caches..
     *
     * @param <O> Observer type.
     * @param observer an optional observer of interim results.
     * @param event the event from which the fetch should be scoped.
     * @param listener the task listener
     * @return the provided observer.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @see SCMEvent#isPayloadTrusted()
     * @since FIXME
     */
    @NonNull
    public final <O extends SCMHeadObserver> O fetch(@NonNull O observer, @NonNull SCMHeadEvent<?> event,
                                                     @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        retrieve(getCriteria(), observer, event, defaultListener(listener));
        return observer;
    }

    /**
     * Fetches the latest heads and corresponding revisions. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     *
     * @param observer an optional observer of interim results.
     * @param listener the task listener.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    protected void retrieve(@NonNull SCMHeadObserver observer, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        if (MethodUtils.isOverridden(SCMSource.class, getClass(), "retrieve",
                SCMSourceCriteria.class, SCMHeadObserver.class, TaskListener.class)) {
            retrieve(getCriteria(), observer, listener);
        } else {
            throw new AbstractMethodError("Implement retrieve(SCMSourceCriteria,SCMHeadObserver,TaskListener)");
        }
    }


    /**
     * Fetches the latest heads and corresponding revisions. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     *
     * @param observer an optional observer of interim results.
     * @param listener the task listener.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    private void _retrieve(@CheckForNull SCMSourceCriteria criteria,
                           @NonNull SCMHeadObserver observer,
                           @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        if (MethodUtils.isOverridden(SCMSource.class, getClass(), "retrieve",
                SCMSourceCriteria.class, SCMHeadObserver.class, TaskListener.class)) {
            // w00t this is a new implementation
            retrieve(criteria, observer, listener);
        } else if (MethodUtils.isOverridden(SCMSource.class, getClass(), "retrieve",
                SCMHeadObserver.class, TaskListener.class)){
            // oh dear, legacy implementation
            SCMSourceCriteria hopefullyNull = compatibilityHack.get();
            compatibilityHack.set(criteria == null ? nullSCMSourceCriteria : criteria);
            try {
                retrieve(observer, listener);
            } finally {
                if (hopefullyNull != null) {
                    // performance is going to be painful if you are nesting them
                    compatibilityHack.set(hopefullyNull);
                } else {
                    compatibilityHack.remove();
                }
            }
        } else {
            throw new AbstractMethodError("Implement retrieve(SCMSourceCriteria,SCMHeadObserver,TaskListener)");
        }
    }

    /**
     * Fetches the latest heads and corresponding revisions. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     * <strong>It is vitally important that implementations must periodically call {@link #checkInterrupt()}
     * otherwise it will be impossible for users to interrupt the operation.</strong>
     *
     * @param criteria the criteria to use, if non-{@code null} them implementations <strong>must</strong>filter all
     *                 {@link SCMHead} instances against the
     *                 {@link SCMSourceCriteria#isHead(SCMSourceCriteria.Probe, TaskListener)}
     *                 before passing through to the {@link SCMHeadObserver}.
     * @param observer an optional observer of interim results.
     * @param listener the task listener.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    protected abstract void retrieve(@CheckForNull SCMSourceCriteria criteria,
                                     @NonNull SCMHeadObserver observer,
                                     @NonNull TaskListener listener)
            throws IOException, InterruptedException;

    /**
     * Fetches the latest heads and corresponding revisions that are originating from the supplied event.
     * If the supplied event is one that the implementer trusts, then the implementer may be able to optimize
     * retrieval to minimize round trips.
     * Implementers are free to cache intermediary results but the call must always check the validity of any
     * intermediary caches. Where the event {@link SCMEvent#isPayloadTrusted()}, implementations are free to use
     * the event information to check the validity of any intermediary caches..
     * <p>
     * <strong>It is vitally important that implementations must periodically call {@link #checkInterrupt()}
     * otherwise it will be impossible for users to interrupt the operation.</strong>
     * <p>
     * The default implementation wraps the {@link SCMHeadObserver} using
     * {@link SCMHeadEvent#filter(SCMSource, SCMHeadObserver)} and delegates to
     * {@link #retrieve(SCMSourceCriteria, SCMHeadObserver, TaskListener)}
     *
     * @param criteria the criteria to use, if non-{@code null} them implementations <strong>must</strong>filter all
     *                 {@link SCMHead} instances against the
     *                 {@link SCMSourceCriteria#isHead(SCMSourceCriteria.Probe, TaskListener)}
     *                 before passing through to the {@link SCMHeadObserver}.
     * @param observer an optional observer of interim results.
     * @param event the event from which the operation should be scoped.
     * @param listener the task listener.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @see SCMEvent#isPayloadTrusted()
     * @since FIXME
     */
    protected void retrieve(@CheckForNull SCMSourceCriteria criteria,
                            @NonNull SCMHeadObserver observer,
                            @NonNull SCMHeadEvent<?> event,
                            @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        if (event.isMatch(this)) {
            _retrieve(criteria, event.filter(this, observer), listener);
        }
    }

    /**
     * Fetches the current list of heads. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     *
     * @param listener the task listener
     * @return the current list of heads.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @NonNull
    public final Set<SCMHead> fetch(@CheckForNull TaskListener listener) throws IOException, InterruptedException {
        return retrieve(defaultListener(listener));
    }

    /**
     * Looks up the immediate parent revision(s) of the specified revision within the specified head.
     *
     * @param head     the head to look up the parent revision(s) within.
     * @param revision the revision to lookup the immediate parent(s) of.
     * @param listener the task listener.
     * @return a set of immediate parent revisions of the specified revision. An empty set indicates either that the
     *         parents are unknown or that the revision is a root revision. Where the backing SCM supports merge
     *         tracking there is the potential for multiple parent revisions reflecting that the specified revision
     *         was a merge of more than one revision and thus has more than one parent.
     * @since 0.3
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @NonNull
    public Set<SCMRevision> parentRevisions(@NonNull SCMHead head, @NonNull SCMRevision revision,
                                            @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return Collections.emptySet();
    }

    /**
     * Looks up the immediate parent heads of the specified head within the specified source.
     *
     * @param head     the head to look up the parent head(s) within.
     * @param listener the task listener.
     * @return a map of immediate parent heads of the specified head where the heads are the keys and the revisions
     *         at which the parent relationship was established are the values. An empty map indicates either that the
     *         parents are unknown or that the head is a root head. Where the backing SCM supports merge
     *         tracking there is the potential for multiple parent heads reflecting that the specified head
     *         was a merge of more than one head and thus has more than one parent.
     * @since 0.3
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @NonNull
    public Map<SCMHead, SCMRevision> parentHeads(@NonNull SCMHead head, @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return Collections.emptyMap();
    }

    /**
     * Fetches the current list of heads. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     *
     * @param listener the task listener
     * @return the current list of heads.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @NonNull
    protected Set<SCMHead> retrieve(@NonNull TaskListener listener) throws IOException, InterruptedException {
        return retrieve(getCriteria(), listener);
    }

    /**
     * Fetches the current list of heads. Implementers are free to cache intermediary results
     * but the call must always check the validity of any intermediary caches.
     *
     * @param criteria the criteria to use for identifying heads.
     * @param listener the task listener
     * @return the current list of heads.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @NonNull
    protected Set<SCMHead> retrieve(@CheckForNull SCMSourceCriteria criteria, @NonNull TaskListener listener) throws IOException, InterruptedException {
        return fetch(criteria, SCMHeadObserver.collect(), listener).result().keySet();
    }

    /**
     * Gets the current head revision of the specified head. Does not check this against any {@link SCMSourceCriteria}.
     *
     * @param head     the head.
     * @param listener the task listener
     * @return the revision hash (may be non-deterministic) or {@code null} if the head no longer exists.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @CheckForNull
    public final SCMRevision fetch(@NonNull SCMHead head, @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return retrieve(head, defaultListener(listener));
    }

    /**
     * Gets the current head revision of the specified head. Does not check this against any {@link SCMSourceCriteria}.
     *
     * @param head     the head.
     * @param listener the task listener
     * @return the revision hash (may be non-deterministic) or {@code null} if the head no longer exists.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     */
    @CheckForNull
    protected SCMRevision retrieve(@NonNull SCMHead head, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return fetch(null, SCMHeadObserver.select(head), listener).result();
    }

    /**
     * Looks up a specific thingName based on some SCM-specific set of permissible syntaxes.
     * Delegates to {@link #retrieve(String, TaskListener)}.
     * @param thingName might be a branch name, a tag name, a cryptographic hash, a thingName number, etc.
     * @param listener the task listener (optional)
     * @return a valid thingName object corresponding to the argument, with a usable corresponding head, or null if malformed or not found
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since 1.3
     */
    @CheckForNull
    public final SCMRevision fetch(@NonNull String thingName, @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return retrieve(thingName, defaultListener(listener));
    }

    /**
     * Looks up a specific revision based on some SCM-specific set of permissible syntaxes.
     * The default implementation uses {@link #retrieve(SCMSourceCriteria, SCMHeadObserver, TaskListener)}
     * and looks for {@link SCMHead#getName} matching the argument (so typically only supporting branch names).
     * @param thingName might be a branch name, a tag name, a cryptographic hash, a revision number, etc.
     * @param listener the task listener
     * @return a valid revision object corresponding to the argument, with a usable corresponding head, or null if malformed or not found
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since 1.3
     */
    @CheckForNull
    protected SCMRevision retrieve(@NonNull final String thingName, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return fetch(null, SCMHeadObserver.named(thingName), listener).result();
    }

    /**
     * Looks up suggested revisions that could be passed to {@link #fetch(String, TaskListener)}.
     * There is no guarantee that all returned revisions are in fact valid, nor that all valid revisions are returned.
     * Delegates to {@link #retrieveRevisions}.
     * @param listener the task listener
     * @return a possibly empty set of revision names suggested by the implementation
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since 1.3
     */
    @NonNull
    public final Set<String> fetchRevisions(@CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return retrieveRevisions(defaultListener(listener));
    }

    /**
     * Looks up suggested revisions that could be passed to {@link #fetch(String, TaskListener)}.
     * There is no guarantee that all returned revisions are in fact valid, nor that all valid revisions are returned.
     * By default, calls {@link #retrieve(TaskListener)}, thus typically returning only branch names.
     * @param listener the task listener
     * @return a possibly empty set of revision names suggested by the implementation
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since 1.3
     */
    @NonNull
    protected Set<String> retrieveRevisions(@NonNull TaskListener listener)
            throws IOException, InterruptedException {
        Set<String> revisions = new HashSet<String>();
        for (SCMHead head : retrieve(listener)) {
            revisions.add(head.getName());
        }
        return revisions;
    }

    /**
     * Fetches any actions that should be persisted for objects related to the specified revision. For example,
     * if a {@link Run} is building a specific {@link SCMRevision}, then this method would be called to populate
     * any {@link Action} instances for that {@link Run}. <strong>NOTE: unlike
     * {@link #fetchActions(SCMHead, TaskListener)}, {@link #fetchActions(TaskListener)} or
     * {@link SCMNavigator#fetchActions(SCMNavigatorOwner, TaskListener)}</strong> there is no guarantee that
     * this method will ever be called more than once for any {@link Run}. <strong>
     * {@link #fetchActions(SCMHead, TaskListener)} must have been called at least once before calling this method.
     * </strong>
     *
     * @param revision the {@link SCMRevision}
     * @param listener the listener to report progress on.
     * @return the map of {@link Action} instances to persist, keyed by the class of action. Keys with {@code null}
     * values indicate actions that should be removed if present.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since FIXME
     */
    @NonNull
    public final Map<Class<? extends Action>, Action> fetchActions(@NonNull SCMRevision revision,
                                                                   @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return tidyActionMap(retrieveActions(revision, defaultListener(listener)));
    }

    /**
     * Fetches any actions that should be persisted for objects related to the specified head. For example,
     * if a {@link Job} is associated with a specific {@link SCMHead}, then this method would be called to refresh
     * any {@link Action} instances of that {@link Job}. <strong>{@link #fetchActions(TaskListener)} must have
     * been called at least once before calling this method.</strong>
     *
     * @param head the {@link SCMHead}
     * @param listener the listener to report progress on.
     * @return the map of {@link Action} instances to persist, keyed by the class of action. Keys with {@code null}
     * values indicate actions that should be removed if present.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since FIXME
     */
    @NonNull
    public final Map<Class<? extends Action>, Action> fetchActions(@NonNull SCMHead head,
                                                                   @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return tidyActionMap(retrieveActions(head, defaultListener(listener)));
    }

    /**
     * Fetches any actions that should be persisted for objects related to the specified source. For example,
     * if a {@link Item} is associated with a specific {@link SCMSource}, then this method would be called to refresh
     * any {@link Action} instances of that {@link Item}. <strong>If this {@link SCMSource} belongs to a
     * {@link SCMNavigator} then {@link SCMNavigator#fetchActions(SCMNavigatorOwner, TaskListener)} must have been
     * called at least once before calling this method.</strong>
     *
     * @param listener the listener to report progress on.
     * @return the map of {@link Action} instances to persist, keyed by the class of action. Keys with {@code null}
     * values indicate actions that should be removed if present.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since FIXME
     */
    @NonNull
    public final Map<Class<? extends Action>, Action> fetchActions(@CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        return tidyActionMap(retrieveActions(defaultListener(listener)));
    }

    /**
     * SPI for {@link #fetchActions(SCMRevision, TaskListener)}. Fetches any actions that should be persisted for
     * objects related to the specified revision.
     *
     * @param revision the {@link SCMRevision}
     * @param listener the listener to report progress on.
     * @return the map of {@link Action} instances to persist, keyed by the class of action. Keys with {@code null}
     * values indicate actions that should be removed if present.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since FIXME
     */
    @NonNull
    protected Map<Class<? extends Action>, Action> retrieveActions(@NonNull SCMRevision revision,
                                                                   @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return Collections.emptyMap();
    }

    /**
     * SPI for {@link #fetchActions(SCMHead, TaskListener)}. Fetches any actions that should be persisted for objects
     * related to the specified head.
     *
     * @param head the {@link SCMHead}
     * @param listener the listener to report progress on.
     * @return the map of {@link Action} instances to persist, keyed by the class of action. Keys with {@code null}
     * values indicate actions that should be removed if present.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since FIXME
     */
    @NonNull
    protected Map<Class<? extends Action>, Action> retrieveActions(@NonNull SCMHead head,
                                                                   @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return Collections.emptyMap();
    }

    /**
     * SPI for {@link #fetchActions(TaskListener)}. Fetches any actions that should be persisted for
     * objects related to the specified source.
     *
     * @param listener the listener to report progress on.
     * @return the map of {@link Action} instances to persist, keyed by the class of action. Keys with {@code null}
     * values indicate actions that should be removed if present.
     * @throws IOException if an error occurs while performing the operation.
     * @throws InterruptedException if any thread has interrupted the current thread.
     * @since FIXME
     */
    @NonNull
    protected Map<Class<? extends Action>, Action> retrieveActions(@NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return Collections.emptyMap();
    }

    static Map<Class<? extends Action>, Action> tidyActionMap(Map<Class<? extends Action>, Action> map) {
        if (map == null) return Collections.emptyMap();
        for (Iterator<Class<? extends Action>> iterator = map.keySet().iterator(); iterator.hasNext(); ) {
            Class<? extends Action> clazz = iterator.next();
            boolean isSubclass = false;
            for (Class<? extends Action> c : map.keySet()) {
                if (clazz == c) {
                    continue;
                }
                if (c.isAssignableFrom(clazz)) {
                    isSubclass = true;
                    break;
                }
            }
            if (isSubclass) {
                iterator.remove();
            }
        }
        return map;
    }

    /**
     * Builds a {@link SCM} instance for the specified head and revision, no validation of the
     * head is performed, a revision for a different head or source will be treated as equivalent to a
     * {@code null} revision.
     *
     * @param head     the head.
     * @param revision the revision or {@code null}.
     * @return the {@link SCM} instance.
     */
    @NonNull
    public abstract SCM build(@NonNull SCMHead head, @CheckForNull SCMRevision revision);

    /**
     * Builds a {@link SCM} instance for the specified head.
     *
     * @param head the head.
     * @return the {@link SCM} instance
     */
    @NonNull
    public final SCM build(@NonNull SCMHead head) {
        return build(head, null);
    }

    /**
     * Enables a source to request that an alternative revision be used to obtain security-sensitive build instructions.
     * <p>Normally it is assumed that revisions in the SCM represented by this source
     * come from principals operating with the same authorization as the principal creating the job,
     * or at least with authorization to create a similar job.
     * <p>A source may however collect revisions from untrusted third parties and submit them for builds.
     * If the project type performing the build loads instructions from the same revision,
     * this might allow the job to be subverted to perform unauthorized build steps or steal credentials.
     * <p>By replacing the supplied revision with a trusted variant, a source can defend against such attacks.
     * It is up to the project type to determine which files should come from a trusted replacement.
     * Regular project sources should come from the original;
     * Jenkins-specific scripting commands or configuration should come from the replacement, unless easily sandboxed;
     * scripts for external build tools should come from the original if possible.
     * @param revision a revision (produced by one of the {@code retrieve} overloads)
     *                 which may or may not come from a trustworthy source
     * @param listener a way to explain possible substitutions
     * @return by default, {@code revision};
     *         may be overridden to provide an alternate revision from the same or a different head
     * @throws IOException in case the implementation must call {@link #fetch(SCMHead, TaskListener)} or similar
     * @throws InterruptedException in case the implementation must call {@link #fetch(SCMHead, TaskListener)} or similar
     * @since 1.1
     */
    @NonNull
    public SCMRevision getTrustedRevision(@NonNull SCMRevision revision, @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        return revision;
    }

    /**
     * Turns a possibly {@code null} {@link TaskListener} reference into a guaranteed non-null reference.
     *
     * @param listener a possibly {@code null} {@link TaskListener} reference.
     * @return guaranteed non-null {@link TaskListener}.
     */
    @NonNull
    protected final TaskListener defaultListener(@CheckForNull TaskListener listener) {
        if (listener == null) {
            Level level;
            try {
                level = Level.parse(System.getProperty(getClass().getName() + ".defaultListenerLevel", "FINE"));
            } catch (IllegalArgumentException e) {
                level = Level.FINE;
            }
            return new LogTaskListener(Logger.getLogger(getClass().getName()), level);
        }
        return listener;
    }

    /**
     * Tests if this {@link SCMSource} can instantiate a {@link SCMSourceCriteria.Probe}
     * @return {@code true} if and only if {@link #createProbe(SCMHead, SCMRevision)} has been implemented.
     * @since FIXME
     */
    public boolean canProbe() {
        return MethodUtils.isOverridden(SCMSource.class, getClass(), "createProbe", SCMHead.class, SCMRevision.class);
    }

    /**
     * Creates a {@link SCMProbe} for the specified {@link SCMHead} and {@link SCMRevision}.
     *
     * Public exposed API for {@link #createProbe(SCMHead, SCMRevision)}.
     * @param head the {@link SCMHead}.
     * @param revision the {@link SCMRevision}.
     * @return the {@link SCMSourceCriteria.Probe}.
     * @throws IllegalArgumentException if the {@link SCMRevision#getHead()} is not equal to the supplied {@link SCMHead}
     * @throws IOException if the probe creation failed due to an IO exception.
     * @see #canProbe()
     * @since FIXME
     */
    @NonNull
    public final SCMProbe newProbe(@NonNull SCMHead head, @CheckForNull SCMRevision revision) throws IOException {
        if (revision != null && !revision.getHead().equals(head)) {
            throw new IllegalArgumentException("Mismatched head and revision");
        }
        try {
            return createProbe(head, revision);
        } catch (AbstractMethodError e) {
            throw new UnsupportedOperationException(e);
        }
    }

    /**
     * Creates a {@link SCMProbe} for the specified {@link SCMHead} and {@link SCMRevision}.
     *
     * @param head the {@link SCMHead}.
     * @param revision the {@link SCMRevision}.
     * @return the {@link SCMSourceCriteria.Probe}.
     * @throws IOException if the probe creation failed due to an IO exception.
     * @see #canProbe()
     * @see #newProbe(SCMHead, SCMRevision)
     * @see #fromSCMFileSystem(SCMHead, SCMRevision)
     * @since FIXME
     */
    @NonNull
    protected SCMProbe createProbe(@NonNull final SCMHead head, @CheckForNull final SCMRevision revision)
            throws IOException {
        throw new AbstractMethodError();
    }

    /**
     * Helper method for subclasses that have implemented a {@link SCMFileSystem.Builder} and want to use a simple
     * non-caching {@link SCMProbe} based off of the {@link SCMFileSystem}.
     *
     * @param head the {@link SCMHead}.
     * @param revision the {@link SCMRevision}.
     * @return the {@link SCMSourceCriteria.Probe} or {@code null} if this source cannot be probed.
     * @since FIXME
     */
    @CheckForNull
    protected final SCMProbe fromSCMFileSystem(@NonNull final SCMHead head, @CheckForNull final SCMRevision revision) {
        final SCMFileSystem fileSystem = SCMFileSystem.of(this, head, revision);
        if (fileSystem != null) {
            // we can build a generic probe from the SCMFileSystem
            //
            return new SCMProbe() {
                /**
                 * {@inheritDoc}
                 */
                @NonNull
                @Override
                public SCMProbeStat stat(@NonNull String path) throws IOException {
                    return SCMProbeStat.fromType(fileSystem.child(path).getType());
                }

                /**
                 * {@inheritDoc}
                 */
                @Override
                public void close() throws IOException {
                    fileSystem.close();
                }

                /**
                 * {@inheritDoc}
                 */
                @Override
                public String name() {
                    return head.getName();
                }

                /**
                 * {@inheritDoc}
                 */
                @Override
                public long lastModified() {
                    try {
                        return fileSystem.lastModified();
                    } catch (IOException e) {
                        return 0L;
                    }
                }

                /**
                 * {@inheritDoc}
                 */
                @Override
                public SCMFile getRoot() {
                    return fileSystem.getRoot();
                }
            };
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SCMSource)) {
            return false;
        }

        SCMSource that = (SCMSource) o;

        return getId().equals(that.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final int hashCode() {
        return getId().hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(getClass().getName());
        sb.append("{id='").append(id).append('\'');
        sb.append('}');
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SCMSourceDescriptor getDescriptor() {
        return (SCMSourceDescriptor) super.getDescriptor();
    }

    /**
     * Get the term used in the UI to represent this kind of {@link SCMSource}. Must start with a capital letter.
     *
     * @return the term or {@code null} to fall back to the calling context's default.
     * @since FIXME
     */
    @CheckForNull
    public String getPronoun() {
        return AlternativeUiTextProvider.get(PRONOUN, this, getDescriptor().getPronoun());
    }

    /**
     * Returns the set of {@link SCMHeadCategory} that this {@link SCMSource} supports. There will always be
     * exactly one {@link SCMCategory#isUncategorized()} instance in the returned set.
     *
     * @return the set of {@link SCMHeadCategory} that this {@link SCMSource} supports.
     * @since FIXME
     */
    @NonNull
    public final Set<? extends SCMHeadCategory> getCategories() {
        Set<? extends SCMHeadCategory> result = getDescriptor().getCategories();
        if (result.size() > 1
                && MethodUtils.isOverridden(SCMSource.class, getClass(), "isCategoryEnabled", SCMHeadCategory.class)) {
            // if result has only one entry then it must be the default, so will never be filtered
            // if we didn't override the category enabled check, then none will be disabled
            result = new LinkedHashSet<SCMHeadCategory>(result);
            for (Iterator<? extends SCMHeadCategory> iterator = result.iterator(); iterator.hasNext(); ) {
                SCMHeadCategory category = iterator.next();
                if (!category.isUncategorized() && !isCategoryEnabled(category)) {
                    // only keep the enabled non-default categories
                    iterator.remove();
                }
            }
        }
        return result;
    }

    /**
     * Sub-classes can override this method to filter the categories that are available from a specific source. For
     * example a source type might be capable of having mainline branches, user branches, merge requests and
     * release tags while a specific instance of the source may be configured to only have mainline branches and
     * release tags.
     *
     * @param category the category.
     * @return {@code true} if the supplied category is enabled for this {@link SCMSource} instance.
     * @since FIXME
     */
    protected boolean isCategoryEnabled(@NonNull SCMHeadCategory category) {
        return true;
    }

    /**
     * Checks the {@link Thread#interrupted()} and throws an {@link InterruptedException} if it was set.
     *
     * @throws InterruptedException if interrupted.
     * @since FIXME
     */
    protected final void checkInterrupt() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    /**
     * Callback from the {@link SCMSourceOwner} after the {@link SCMSourceOwner} has been saved. Can be used to
     * register the {@link SCMSourceOwner} for a call-back hook from the backing SCM that this source is for.
     *
     * @since FIXME
     */
    public void afterSave() {}

}
