package com.siimkinks.unicorn;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.CheckResult;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.transition.Scene;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.siimkinks.unicorn.ContentViewContract.LifecycleEvent;

import java.util.ArrayDeque;
import java.util.Iterator;

import rx.Observer;

import static com.siimkinks.unicorn.ContentViewContract.BackPressResult.NAVIGATE_BACK;
import static com.siimkinks.unicorn.ContentViewContract.BackPressResult.STAY_ON_VIEW;
import static com.siimkinks.unicorn.ContentViewContract.LifecycleEvent.DESTROY;
import static com.siimkinks.unicorn.ContentViewContract.LifecycleEvent.PAUSE;
import static com.siimkinks.unicorn.ContentViewContract.LifecycleEvent.RESUME;
import static com.siimkinks.unicorn.ContentViewContract.LifecycleEvent.UNKNOWN;
import static com.siimkinks.unicorn.Contracts.allMustBeBeNull;
import static com.siimkinks.unicorn.Contracts.mustBeFalse;

/**
 * View stack manager.
 *
 * @param <GraphProvider>
 *         Application dependency injection provider
 */
public class ViewManager<GraphProvider extends DependencyGraphProvider> implements Observer<LifecycleEvent> {
    private static final String TAG = ViewManager.class.getSimpleName();

    @VisibleForTesting
    ArrayDeque<NavigationDetails> viewStack = new ArrayDeque<>(5);

    private final GraphProvider graphProvider;

    @Nullable
    private Activity foregroundActivity;
    private LayoutInflater inflater;
    private ViewGroup contentRootView;
    private LifecycleEvent latestParentLifecycleEvent = UNKNOWN;

    public ViewManager(@NonNull GraphProvider graphProvider) {
        this.graphProvider = graphProvider;
    }

    /**
     * Call when root activity is available.
     * <p>
     * A good place for that is {@code {@link Activity#onCreate(Bundle)}} for example.
     *
     * @param activity
     *         that one root activity
     */
    @MainThread
    public final void registerActivity(@NonNull RootActivityContract activity) {
        allMustBeBeNull("Trying to register new foreground activity, but old resources are not cleaned",
                foregroundActivity, contentRootView, inflater);
        this.foregroundActivity = (Activity) activity;
        this.inflater = foregroundActivity.getLayoutInflater();
        this.contentRootView = activity.getContentRootView();
        activity.hookIntoLifecycle(this);
        if (viewStack.isEmpty()) {
            renderFirstView(activity.getFirstView());
        } else {
            reRenderTopView();
        }
    }

    /**
     * Call when root activity is not available anymore.
     * <p>
     * A good place for that is {@code {@link Activity#onDestroy()}} for example.
     */
    @MainThread
    public final void unregisterActivity() {
        foregroundActivity = null;
        contentRootView = null;
        inflater = null;
        final ArrayDeque<NavigationDetails> newStack = new ArrayDeque<>(viewStack.size());
        final Iterator<NavigationDetails> iterator = viewStack.descendingIterator();
        while (iterator.hasNext()) {
            final NavigationDetails next = iterator.next();
            next.view().onDestroy();
            newStack.push(next.markRestartNeeded());
        }
        viewStack = newStack;
    }

    /**
     * Receive parent activity lifecycle events.
     *
     * @param lifecycleEvent
     *         Parent lifecycle event
     */
    @Override
    public void onNext(LifecycleEvent lifecycleEvent) {
        switch (lifecycleEvent) {
            case PAUSE:
                pauseTopView();
                break;
            case RESUME:
                // we only resume top view if parents' last lifecycle was PAUSE -- otherwise
                // we violate view contract
                if (latestParentLifecycleEvent == PAUSE) {
                    resumeTopView();
                }
                break;
            default:
                break;
        }
        latestParentLifecycleEvent = lifecycleEvent;
    }

    // Parent activity lifecycle onComplete
    @Override
    public void onCompleted() {
    }

    // Parent activity lifecycle onError
    @Override
    public void onError(Throwable e) {
    }

    @MainThread
    void navigate(@NonNull NavigationDetails navDetails) {
        boolean newInstance = true;
        if (navDetails.singleInstance() && !viewStack.isEmpty()) {
            if (viewStack.peek().equals(navDetails)) {
                return;
            }
            final Dual<NavigationDetails, Boolean> removedViewMetadata = removeViewFromStack(navDetails.view());
            if (removedViewMetadata != null) {
                // bring view to top, but with new nav details
                final NavigationDetails removedViewDetails = removedViewMetadata.first();
                if (removedViewDetails.needsRestart()) {
                    // if view needs restart then it's a new instance
                    // also, prev view needs restart -- therefore view.copy()
                    navDetails = navDetails.copy()
                            .view(removedViewDetails.view().copy())
                            .build();
                } else {
                    navDetails = navDetails.copy()
                            .view(removedViewDetails.view())
                            .build();
                    newInstance = false;
                }
            }
        }
        if (navDetails.clearStack()) {
            clearStack();
        }

        // there might not be root view currently -- activity dead or smth like that
        if (contentRootView != null && isActivityShown()) {
            pushViewToStack(navDetails, newInstance);
        } else {
            // we wait for root view to come back -- until then just mark this view as needing restart
            LogUtil.logViewDebug(TAG, "No root view while navigating to view %s. Adding it to stack and waiting for root view to come back",
                    navDetails.view().getClass().getSimpleName());
            viewStack.push(navDetails.markRestartNeeded());
        }
    }

    @MainThread
    private void renderFirstView(@NonNull NavigationDetails firstViewNavDetails) {
        viewStack.push(firstViewNavDetails);
        final ContentViewContract firstView = firstViewNavDetails.view();
        inflater.inflate(firstView.getViewResId(), contentRootView);
        firstView.setRootView(getContentRootViewFirstChild());
        callViewStartMethods(firstView, true);
    }

    @MainThread
    private void reRenderTopView() {
        final NavigationDetails first = viewStack.pollFirst();
        navigate(first.restart());
    }

    @MainThread
    private void pushViewToStack(@NonNull NavigationDetails newViewNavigationDetails, boolean newInstance) {
        final ContentViewContract view = newViewNavigationDetails.view();
        final NavigationDetails prevView = pauseTopView();
        viewStack.push(newViewNavigationDetails);

        transitionBetween(newViewNavigationDetails, prevView);

        callViewStartMethods(view, newInstance);
    }

    @MainThread
    private void callViewStartMethods(@NonNull ContentViewContract view, boolean newInstance) {
        if (newInstance) {
            //noinspection unchecked
            view.onCreate(graphProvider);
        }
        if (view.latestLifecycleEvent() != DESTROY) { // check if view called finish in onCreate
            view.onResume();
        }
    }

    @Nullable
    private NavigationDetails pauseTopView() {
        NavigationDetails topView = null;
        if (!viewStack.isEmpty()) {
            topView = viewStack.peek();
            // prev view waits for restart (aka destroyed), so contract
            // permits us from calling any lifecycle methods on it
            if (!topView.needsRestart()) {
                topView.view().onPause();
            }
        }
        return topView;
    }

    /**
     * This method is intended to be called only when activity resumes itself!
     */
    private void resumeTopView() {
        if (!viewStack.isEmpty()) {
            final NavigationDetails topView = viewStack.peek();

            mustBeFalse(topView.needsRestart(), "Tried to resume top view that needs restart");

            topView.view().onResume();
        }
    }

    /**
     * Call from the {@link Activity#onBackPressed()}
     *
     * @return {@code true} when back press was consumed. {@code false} when system method
     * should be called
     */
    @MainThread
    public final boolean handleBackPress() {
        final NavigationDetails top = viewStack.peek();
        final ContentViewContract topView = top.view();
        final ContentViewContract.BackPressResult backPressResult = topView.onBackPressed();
        if (backPressResult == STAY_ON_VIEW) {
            return true;
        }
        if (backPressResult == NAVIGATE_BACK && canNavigateBack()) {
            finish(topView);
            return true;
        }
        return false;
    }

    @MainThread
    void finish(@NonNull ContentViewContract view) {
        final Dual<NavigationDetails, Boolean> removedViewMetadata = removeViewFromStack(view);
        if (removedViewMetadata == null) {
            LogUtil.logViewWarning(TAG, "Tried to remove non-existing view %s; ignore and carry on", view);
            return;
        }
        final Boolean top = removedViewMetadata.second();
        final NavigationDetails removed = removedViewMetadata.first();
        final ContentViewContract removedView = removed.view();
        final LifecycleEvent latestLifecycleEvent = removedView.latestLifecycleEvent();
        // if we are top we enter normal finish flow
        // if last lifecycle event was PAUSE then view called finish unexpectedly in onPause and we enter unconventional flow
        if (top && latestLifecycleEvent != PAUSE) {
            removedView.onPause();
        }
        removedView.onDestroy();
        if (top && latestLifecycleEvent != PAUSE) {
            final NavigationDetails newTop = viewStack.peek();
            if (newTop != null) {
                if (newTop.needsRestart()) {
                    final NavigationDetails restartedNavDetails = viewStack.pollFirst().restart();
                    viewStack.push(restartedNavDetails);
                    transitionBetween(restartedNavDetails, removed);
                    callViewStartMethods(restartedNavDetails.view(), true);
                } else {
                    transitionBetween(newTop, removed);
                    final ContentViewContract newTopView = newTop.view();
                    // if on destroy new view is opened then resume for this newly added view is already called
                    if (newTopView.latestLifecycleEvent() != RESUME) {
                        newTopView.onResume();
                    }
                }
            }
        }
    }

    @MainThread
    private void clearStack() {
        final Iterator<NavigationDetails> iterator = viewStack.iterator();
        while (iterator.hasNext()) {
            final NavigationDetails next = iterator.next();
            if (!next.needsRestart()) { // this view is already destroyed
                next.view().onDestroy();
            }
            iterator.remove();
        }
    }

    @SuppressWarnings("deprecation")
    @VisibleForTesting
    @MainThread
    void transitionBetween(@NonNull NavigationDetails entering, @Nullable NavigationDetails leaving) {
        final ContentViewContract enteringView = entering.view();
        final ViewGroup enteringRootView = enteringView.getRootView();
        if (enteringRootView == null) {
            final int contentViewResId = enteringView.getViewResId();
            final Scene nextViewScene = Scene.getSceneForLayout(this.contentRootView, contentViewResId, foregroundActivity);
            final TransitionSet transition = ViewTransition.from(entering, leaving);
            TransitionManager.go(nextViewScene, transition);
            enteringView.setRootView(getContentRootViewFirstChild());
        } else {
            final Scene nextViewScene = new Scene(this.contentRootView, enteringRootView);
            final TransitionSet transition = ViewTransition.from(entering, leaving);
            TransitionManager.go(nextViewScene, transition);
        }
    }

    @VisibleForTesting
    @Nullable
    @MainThread
    Dual<NavigationDetails, Boolean> removeViewFromStack(@NonNull ContentViewContract removableView) {
        final Iterator<NavigationDetails> iterator = viewStack.iterator();
        boolean top = true;
        while (iterator.hasNext()) {
            final NavigationDetails next = iterator.next();
            final ContentViewContract nextViewContract = next.view();
            final boolean equal = removableView.equals(nextViewContract);
            if (top) {
                if (equal) {
                    iterator.remove();
                    return Dual.create(next, Boolean.TRUE);
                }
                top = false;
            } else if (equal) {
                iterator.remove();
                return Dual.create(next, Boolean.FALSE);
            }
        }
        return null;
    }

    private boolean canNavigateBack() {
        return viewStack.size() > 1;
    }

    @NonNull
    private View getContentRootViewFirstChild() {
        return contentRootView.getChildAt(0);
    }

    @CheckResult
    private boolean isActivityShown() {
        if (foregroundActivity == null) {
            return false;
        }
        return ((RootActivityContract) foregroundActivity).latestLifecycleEvent().isVisible();
    }
}

