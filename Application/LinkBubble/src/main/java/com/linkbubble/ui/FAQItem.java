package com.linkbubble.ui;

import android.content.Context;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.linkbubble.R;


class FAQItem extends LinearLayout {
    private TextView mQuestionTextView;
    private TextView mAnswerTextView;

    public FAQItem(Context context) {
        this(context, null);
    }

    public FAQItem(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FAQItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    void configure(BaseAdapter adapter, int questionStringId, int answerStringId, boolean expanded) {
        setTag(adapter);

        if (mQuestionTextView == null) {
            mQuestionTextView = (TextView)findViewById(R.id.question_text_view);
        }
        if (mAnswerTextView == null) {
            mAnswerTextView = (TextView)findViewById(R.id.answer_text_view);
        }

        mQuestionTextView.setText(questionStringId);

        String answerString = getContext().getString(answerStringId);
        //if (answerString.matches(".*\\<[^>]+>.*")) {
        /*
        if (answerString.contains("href=") || answerString.contains("<img")) {
            mAnswerTextView.setText(Html.fromHtml(answerString));
            mAnswerTextView.setMovementMethod(LinkMovementMethod.getInstance());

            mQuestionTextView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mAnswerTextView.setVisibility(mAnswerTextView.getVisibility() == VISIBLE ? GONE : VISIBLE);
                    v.requestLayout();
                    ViewParent parent = v.getParent();
                    do {
                        if (parent instanceof ListView) {
                            BaseAdapter baseAdapter = (BaseAdapter)((ListView) parent).getAdapter();
                            baseAdapter.notifyDataSetChanged();
                            break;
                        }
                        parent = parent.getParent();
                    } while (parent != null);
                }
            });
        } else {
            mAnswerTextView.setText(answerString);
        }*/
        mAnswerTextView.setText(Html.fromHtml(answerString));
        mAnswerTextView.setVisibility(expanded ? VISIBLE : GONE);
    }
}

