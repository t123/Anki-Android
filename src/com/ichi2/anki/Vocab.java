/***************************************************************************************
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Vibrator;
import android.text.ClipboardManager;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.ichi2.utils.DiffEngine;
import com.ichi2.utils.RubyParser;
import com.tomgibara.android.veecheck.util.PrefSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Vocab extends Activity {
	private final int DIRECTION_FORWARD = 1; //Question first
	private final int DIRECTION_REVERSE = 2; //Answer first

	//Constants that should come from preferences
	private final int CARDS_PER_PAGE = 7;
	private final int CARDS_TO_FETCH_MAX = 30;
	private final String FIELD_FRONT = "VocabFront"; //Field on front of the card
	private final String FIELD_BACK = "VocabBack"; //Field on reverse of the card
	private final String FIELD_TAG = "ankidroidvocab"; //Limit to this tag
	
	private static final String[] newOrderStrings = 
	{ 
		"priority desc, due", 
		"priority desc, due",
		"priority desc, due desc" 
	};
	
	private ArrayList<HashMap<String,String>> _vocabWords;
	private SimpleAdapter _vocabAdapter; 
	
	private int mCurrentPage = 1;
	
    /**
     * Broadcast that informs us when the sd card is about to be unmounted
     */
    private BroadcastReceiver mUnmountReceiver = null;

    /**
     * Variables to hold preferences
     */
//    private boolean mPrefFullscreenReview;
//    private String mDeckFilename;
    
    /**
     * Variables to hold layout objects that we need to update or handle events for
     */
    private int DIRECTION_CURRENT = DIRECTION_FORWARD;
    
    private Button mPrev;
    private Button mShuffle;
    private Button mReverse;
    private Button mNext;
    private ListView lvVocabWords;
    
    @SuppressWarnings("unused")
	private boolean mConfigurationChanged = false;


    // ----------------------------------------------------------------------------
    // LISTENERS
    // ----------------------------------------------------------------------------
    
    private View.OnClickListener mSelectOptionHandler = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.vocab_previous:
                	if(mCurrentPage>1) {
                		mCurrentPage--;
                		bindWords();
                	}
                    break;
                    
                case R.id.vocab_shuffle:
                	Collections.shuffle(_vocabWords);
                	mCurrentPage = 1;
                	bindWords();
                    break;
                    
                case R.id.vocab_reverse:
                	DIRECTION_CURRENT =  (DIRECTION_CURRENT==DIRECTION_FORWARD) 
                				? 
                				DIRECTION_REVERSE : 
                				DIRECTION_FORWARD;
                	
                	bindWords();
                	break;
                	
                case R.id.vocab_next:
                	int totalPages = (int)Math.ceil((double)_vocabWords.size() / (double)CARDS_PER_PAGE);
                	
                	if(mCurrentPage<totalPages) {
                		mCurrentPage++;
                		bindWords();
                	}
                    break;

                default:
                    return;
            }
        }
    };
    
    private AdapterView.OnItemLongClickListener lvItemLongClickListener = new AdapterView.OnItemLongClickListener() {

		@Override
		public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
			LinearLayout ll = (LinearLayout)view;
			
			if(ll==null)
				return true;
			
			TextView tv = (TextView)view.findViewById(R.id.vocab_item_question);
			
			if(tv==null)
				return true;
			
			int wordIndex = (mCurrentPage-1)*CARDS_PER_PAGE + position;
			
			if(wordIndex<0 || wordIndex>_vocabWords.size())
				return true;
			
			HashMap<String,String> data = _vocabWords.get(wordIndex);
			
			if(data==null)
				return true;
			
			String query = String.format(
					"select b.name, a.value " +
					"from fields a, fieldmodels b " +
					"where  " +
					"	a.factId=%s and " +
					"	a.fieldModelId=b.id and (b.name!='%s' and b.name!='%s') " +
					"order by a.ordinal",
					data.get("id"),
					FIELD_BACK,
					FIELD_FRONT
					);
					
			Cursor cur = null;
			Deck deck = AnkiDroidApp.deck();
	        try {
	        	cur = deck.getDB().getDatabase().rawQuery(query, null);
	        	StringBuilder sb = new StringBuilder();
	        	
	        	while(cur.moveToNext()) {
	        		String name = Utils.stripHTML(cur.getString(0));
	        		String value = Utils.stripHTML(cur.getString(1));
	        	
	        		sb.append(String.format("%s: %s\n", name, value));
	        	}
	        	cur.close();
	        	
	        	new AlertDialog.Builder(view.getContext())
	                .setTitle("Card Definition")
	                .setMessage(sb.toString())
	                .setPositiveButton(android.R.string.ok, null)
	                .show();
	        }
            catch(SQLException e) {
            	throw new RuntimeException(e);
            }
            finally {
            	if(cur!=null && !cur.isClosed())
            		cur.close();
            }
            
			return true;
		}
    };
    
    private AdapterView.OnItemClickListener lvItemClickListener = new AdapterView.OnItemClickListener() {

		@Override
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			LinearLayout ll = (LinearLayout)view;
			
			if(ll==null)
				return;
			
			TextView tv = (TextView)view.findViewById(R.id.vocab_item_question);
			
			if(tv==null)
				return;
			
			int wordIndex = (mCurrentPage-1)*CARDS_PER_PAGE + position;
			
			if(wordIndex<0 || wordIndex>_vocabWords.size())
				return;
			
			HashMap<String,String> data = _vocabWords.get(wordIndex);
			
			if(data==null)
				return;
			
			if(tv.getText().equals(data.get("question"))) {
				tv.setText(data.get("answer"));
			} else if(tv.getText().equals(data.get("answer"))) {
				tv.setText(data.get("question"));
			} else {
				tv.setText(data.get("question"));
			}
		}
	};

	// ----------------------------------------------------------------------------
    // ANDROID METHODS
    // ----------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(AnkiDroidApp.TAG, "Vocab - onCreate");

        // Make sure a deck is loaded before continuing.
        if (AnkiDroidApp.deck() == null) {
            setResult(StudyOptions.CONTENT_NO_EXTERNAL_STORAGE);
            closeVocab();
        } else {
//            restorePreferences();

            // Remove the status bar and title bar
//            if (mPrefFullscreenReview) {
//                getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
//                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
//                requestWindowFeature(Window.FEATURE_NO_TITLE);
//            }

            requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

            registerExternalStorageListener();

            initLayout(R.layout.vocab);

            populateVocabWords();
        }
    }
    
    private void populateVocabWords() {
    	Deck deck = AnkiDroidApp.deck();
    	
    	if(deck==null) {
    		closeVocab();
    		return;
    	}
    	
    	_vocabWords = new ArrayList<HashMap<String,String>>(CARDS_TO_FETCH_MAX);

    	String tagLimit = "";
    	
    	if(!FIELD_TAG.equals("")) {
    		tagLimit = String.format(" and b.tags like '%%%s%%' ", FIELD_TAG);
    	}
    	
    	final String questionQuery = String.format(
    			"select a.factId, a.value from fields a, facts b, fieldmodels c " +
    			"where " +
				"	c.name='%s' and " +
    			"	a.fieldmodelid=c.id and " +
    			"	a.factid in (select factid from cards where type=2 and factId not in (select distinct factId from cards where type!=2) order by %s) and " +
    			"	a.factid=b.id %s " +
    			"limit %d",
    			FIELD_FRONT,
    			newOrderStrings[deck.getNewCardOrder()],
    			tagLimit,
    			CARDS_TO_FETCH_MAX
    		);
    	
    	final String answerQuery = String.format(
    			"select a.factId, a.value from fields a, facts b, fieldmodels c " +
    			"where " +
				"	c.name='%s' and " +
    			"	a.fieldmodelid=c.id and " +
    			"	a.factid in (select factid from cards where type=2 and factId not in (select distinct factId from cards where type!=2)) and " +
    			"	a.factid=b.id %s " +
    			"limit %d",
    			FIELD_BACK,
    			tagLimit,
    			CARDS_TO_FETCH_MAX
    		);
    	
    	Cursor cur = null;
    	
        try {
        	cur = deck.getDB().getDatabase().rawQuery(questionQuery, null);
        	
            while (cur.moveToNext()) {
            	String factId = Long.toString(cur.getLong(0));
            	String value = cur.getString(1).trim();
            	
            	if(value.equals(""))
            		continue;
            	
            	HashMap<String, String> data = new HashMap<String, String>();
                data.put("id", factId);
                data.put("question",  Utils.stripHTML(value));
                
                _vocabWords.add(data);
            }
            
            cur.close();
            cur = deck.getDB().getDatabase().rawQuery(answerQuery, null);
            
            while (cur.moveToNext()) {
            	String factId = Long.toString(cur.getLong(0));
            	String value = cur.getString(1);
            	
            	for(HashMap<String,String> temp : _vocabWords) {
            		if(temp.get("id").equals(factId)) {
            			temp.put("answer",  Utils.stripHTML(value));
            		}
            	}
            }
            
            Log.i(AnkiDroidApp.TAG, String.format("populateVocabWords: fetched %d words", _vocabWords.size()));
        } catch (SQLException e) {
            Log.e(AnkiDroidApp.TAG, "populateVocabWords: " + e.toString());
            throw new RuntimeException(e);
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        
        bindWords();
    }
    
    protected void bindWords() {
    	int startItem = (mCurrentPage-1)*CARDS_PER_PAGE;
    	int endItem = startItem + CARDS_PER_PAGE;
    	
    	if(endItem>_vocabWords.size()) {
    		endItem = _vocabWords.size();
    	}
    	
    	List<HashMap<String,String>> tempWords = _vocabWords.subList(startItem, endItem);
    	
    	String bind;
    	
    	if(DIRECTION_CURRENT==DIRECTION_FORWARD) {
    		bind = "question";
    	} else {
    		bind = "answer";
    	}
    	
    	_vocabAdapter = new SimpleAdapter(
    			this,
    			tempWords,
    			R.layout.vocab_item,
    			new String[] { bind },
    			new int[] { R.id.vocab_item_question }
		);
    	
    	lvVocabWords.setAdapter(_vocabAdapter);
    	_vocabAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onDestroy();
        Log.i(AnkiDroidApp.TAG, "Vocab - onResume()");
        populateVocabWords();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(AnkiDroidApp.TAG, "Vocab - onDestroy()");
        if (mUnmountReceiver != null) {
            unregisterReceiver(mUnmountReceiver);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        Log.i(AnkiDroidApp.TAG, "onConfigurationChanged");
        
        mConfigurationChanged = true;

        initLayout(R.layout.vocab);
        
        mConfigurationChanged = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ----------------------------------------------------------------------------
    // CUSTOM METHODS
    // ----------------------------------------------------------------------------

    /**
     * Registers an intent to listen for ACTION_MEDIA_EJECT notifications. The intent will call
     * closeExternalStorageFiles() if the external media is going to be ejected, so applications can clean up any files
     * they have open.
     */
    private void registerExternalStorageListener() {
        if (mUnmountReceiver == null) {
            mUnmountReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                        Log.i(AnkiDroidApp.TAG, "mUnmountReceiver - Action = Media Eject");
                        finishNoStorageAvailable();
                    }
                }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(Intent.ACTION_MEDIA_EJECT);
            iFilter.addDataScheme("file");
            registerReceiver(mUnmountReceiver, iFilter);
        }
    }


    private void finishNoStorageAvailable() {
        setResult(StudyOptions.CONTENT_NO_EXTERNAL_STORAGE);
        closeVocab();
    }

    // Set the content view to the one provided and initialize accessors.
    private void initLayout(Integer layout) {
        setContentView(layout);
        
        mPrev = (Button) findViewById(R.id.vocab_previous);
        mShuffle = (Button) findViewById(R.id.vocab_shuffle);
        mReverse = (Button) findViewById(R.id.vocab_reverse);
        mNext = (Button) findViewById(R.id.vocab_next);
        lvVocabWords = (ListView)findViewById(R.id.vocab_words);
        
        mPrev.setOnClickListener(mSelectOptionHandler);
        mShuffle.setOnClickListener(mSelectOptionHandler);
        mReverse.setOnClickListener(mSelectOptionHandler);
        mNext.setOnClickListener(mSelectOptionHandler);
        lvVocabWords.setOnItemClickListener(lvItemClickListener);
        lvVocabWords.setOnItemLongClickListener(lvItemLongClickListener);
    }

//    private SharedPreferences restorePreferences() {
//        SharedPreferences preferences = PrefSettings.getSharedPrefs(getBaseContext());
//
//        mDeckFilename = preferences.getString("deckFilename", "");
//        mPrefFullscreenReview = preferences.getBoolean("fullscreenReview", true);
//
//        return preferences;
//    }

    private void closeVocab() {
    	finish();
    }
}
