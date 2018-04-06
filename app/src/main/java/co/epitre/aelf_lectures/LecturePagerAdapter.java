package co.epitre.aelf_lectures;

import java.util.List;

import co.epitre.aelf_lectures.data.LectureItem;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.util.Log;
import android.view.ViewGroup;

/**
 * Adapter, return a fragment for each lecture / slide.
 */
class LecturePagerAdapter extends FragmentStatePagerAdapter {
    public static final String TAG = "LecturePagerAdapter";

    private List<LectureItem> mlectures;
    private LectureFragment currentLectureFragment;
    private int mStartPosition = -1;
    private String mStartFocusedVerseId = "";

    LecturePagerAdapter(FragmentManager fm, List<LectureItem> lectures, int startPosition, String startFocusedVerseId) {
        super(fm);
        mlectures = lectures;
        mStartPosition = startPosition;
        mStartFocusedVerseId = startFocusedVerseId;
    }

    public LectureFragment getCurrentLectureFragment() {
        return currentLectureFragment;
    }

    @Override
    public Fragment getItem(int position) {
        // getItem is called to instantiate the fragment for the given page.
        LectureItem lecture = mlectures.get(position);
        Fragment fragment = new LectureFragment();

        Bundle args = new Bundle();
        args.putString(LectureFragment.ARG_TEXT_HTML, lecture.description);
        if (mStartPosition == position) {
            mStartPosition = -1;
            args.putString(LectureFragment.ARG_FOCUSED_VERSE_ID, mStartFocusedVerseId);
        }
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public int getCount() {
        return mlectures.size();
    }

    @Override
    public CharSequence getPageTitle(int position) {
        if(position < this.getCount()) {
            return mlectures.get(position).shortTitle;
        }
        return null;
    }

    public LectureItem getLecture(int position) {
        if(position < this.getCount()) {
            return mlectures.get(position);
        }
        return null;
    }

    @Override
    public void finishUpdate(ViewGroup container) {
        // https://stackoverflow.com/questions/41650721/attempt-to-invoke-virtual-method-android-os-handler-android-support-v4-app-frag
        try{
            super.finishUpdate(container);
        } catch (NullPointerException nullPointerException){
            Log.w(TAG, "Catch the NullPointerException in FragmentPagerAdapter.finishUpdate");
        }
    }

    @Override
    public void setPrimaryItem(ViewGroup container, int position, Object object) {
        currentLectureFragment = ((LectureFragment) object);
        super.setPrimaryItem(container, position, object);
    }
}