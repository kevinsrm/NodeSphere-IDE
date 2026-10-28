package com.mobile.nodeide;

import android.content.Context;
import android.util.AttributeSet;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.widget.AppCompatEditText;
import java.util.*;
import java.util.regex.*;

/** Lightweight lexical highlighting and keyword completion, without a language server. */
public class CodeEditor extends AppCompatEditText {
    private static final String[] WORDS={"const","let","var","function","return","if","else","import","from","export","default","async","await","class","new","true","false","null","interface","type","extends","console","require","module","process","document","window","fetch","express"};
    boolean coloring,completionEnabled; String filename=""; PopupWindow popup;
    public CodeEditor(Context c,AttributeSet a){super(c,a);addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){}public void afterTextChanged(Editable e){highlight(e);if(completionEnabled&&hasFocus())post(CodeEditor.this::suggest);}});}
    void setFilename(String name){filename=name;dismiss();highlight(getText());}
    void setCompletionEnabled(boolean enabled){completionEnabled=enabled;if(!enabled)dismiss();}
    void dismiss(){if(popup!=null){popup.dismiss();popup=null;}}
    void suggest(){if(!completionEnabled||!hasFocus()||getWindowToken()==null)return;Editable text=getText();int end=getSelectionStart();if(text==null||end<1||end>text.length()){dismiss();return;}int start=end;while(start>0&&Character.isJavaIdentifierPart(text.charAt(start-1)))start--;if(end-start<2){dismiss();return;}String prefix=text.subSequence(start,end).toString();List<String> matches=new ArrayList<>();for(String word:WORDS)if(word.startsWith(prefix)&&!word.equals(prefix))matches.add(word);if(matches.isEmpty()){dismiss();return;}dismiss();ListView list=new ListView(getContext());list.setBackgroundColor(0xff302d50);list.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_list_item_1,matches));final int begin=start,finish=end;list.setOnItemClickListener((p,v,pos,id)->{if(getSelectionStart()==finish){getText().replace(begin,finish,matches.get(pos));}dismiss();});popup=new PopupWindow(list,Math.max(240,getWidth()/2),Math.min(matches.size(),4)*60,true);popup.setOutsideTouchable(true);popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xff302d50));popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.showAsDropDown(this,0,-Math.min(getHeight(),300));}
    void highlight(Editable e){if(coloring||e==null)return;coloring=true;try{for(ForegroundColorSpan s:e.getSpans(0,e.length(),ForegroundColorSpan.class))e.removeSpan(s);String ext=filename.toLowerCase(Locale.ROOT);if(ext.endsWith(".md")){paint(e,"(?m)^#{1,6} .*$|\\*\\*[^*]+\\*\\*|`[^`]+`",0xffc49fff);}else{paint(e,"(?m)//.*$|/\\*[\\s\\S]*?\\*/|<!--([\\s\\S]*?)-->",0xff829589);paint(e,"</?[a-zA-Z][a-zA-Z0-9:-]*|/?>|\\{\\{[^}]*}}",0xffe9a0c1);paint(e,"\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`",0xffa7dd9a);paint(e,"\\b(?:const|let|var|function|return|if|else|import|from|export|default|async|await|class|new|true|false|null|interface|type|extends)\\b",0xffc49fff);paint(e,"\\b[0-9]+\\b",0xffffa36c);}}finally{coloring=false;}}
    void paint(Editable e,String regex,int color){Matcher m=Pattern.compile(regex).matcher(e);while(m.find())e.setSpan(new ForegroundColorSpan(color),m.start(),m.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
    @Override protected void onDetachedFromWindow(){dismiss();super.onDetachedFromWindow();}
}
