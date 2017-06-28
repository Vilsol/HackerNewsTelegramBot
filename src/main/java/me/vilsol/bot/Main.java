package me.vilsol.bot;

import com.firebase.client.*;
import com.google.api.client.repackaged.com.google.common.base.Joiner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.ocpsoft.prettytime.PrettyTime;
import pro.zackpollard.telegrambot.api.TelegramBot;
import pro.zackpollard.telegrambot.api.chat.Chat;
import pro.zackpollard.telegrambot.api.chat.message.MessageCallbackQuery;
import pro.zackpollard.telegrambot.api.chat.message.send.SendableTextMessage;
import pro.zackpollard.telegrambot.api.event.Listener;
import pro.zackpollard.telegrambot.api.event.chat.CallbackQueryReceivedEvent;
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardButton;
import pro.zackpollard.telegrambot.api.keyboards.InlineKeyboardMarkup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Main {
    
    public static void main(String[] args) throws IOException{
        new Main(args[0], args[1]);
    }
    
    private static final String THUMBS_UP = "\uD83D\uDC4D";
    private static final String THUMBS_DOWN = "\uD83D\uDC4E";
    
    private HashMap<Long, PostData> parsed = new HashMap<>();
    private PrettyTime prettyTime = new PrettyTime();
    
    private Firebase firebase;
    private Chat chat;
    
    public Main(String apiKey, String chatId) throws IOException{
        TelegramBot telegramBot = TelegramBot.login(apiKey);
        telegramBot.startUpdates(false);
        System.out.println(telegramBot.getBotName());
    
        chat = telegramBot.getChat(chatId);
        System.out.println("Connected to '" + chat.getName() + "' chat!");
    
        if(new File("parsed.json").exists()){
            String joined = Joiner.on("").join(Files.readAllLines(Paths.get("parsed.json")));
            
            if(joined.startsWith("[")){
                JSONArray loaded = new JSONArray(joined);
                for(int i = 0; i < loaded.length(); i++){
                    parsed.put(loaded.getLong(i), new PostData(loaded.getLong(i), new HashSet<>(), new HashSet<>()));
                }
            }else{
                JSONObject loaded = new JSONObject(joined);
                loaded.keys().forEachRemaining(k -> {
                    JSONObject data = loaded.getJSONObject(k);
                    long id = Long.parseLong(k);
                    HashSet<Long> up = getSet(data.getJSONArray("up"));
                    HashSet<Long> down = getSet(data.getJSONArray("down"));
                    parsed.put(id, new PostData(id, up, down));
                });
            }
    
            System.out.println("Loaded " + parsed.size() + " parsed posts!");
        }
        
        telegramBot.getEventsManager().register(new Listener() {
            @Override
            public void onCallbackQueryReceivedEvent(CallbackQueryReceivedEvent event){
                event.getCallbackQuery().answer(null, false);
                
                boolean up = event.getCallbackQuery().getData().startsWith("u");
                PostData data = parsed.get(Long.parseLong(event.getCallbackQuery().getData().split(":")[1]));
                long user = event.getCallbackQuery().getFrom().getId();
                
                boolean reload = false;
                if(up && !data.upVotes.contains(user)){
                    data.downVotes.remove(user);
                    data.upVotes.add(user);
                    reload = true;
                }else if(!up && !data.downVotes.contains(user)){
                    data.upVotes.remove(user);
                    data.downVotes.add(user);
                    reload = true;
                }
                
                if(reload){
                    save();
    
                    InlineKeyboardButton thumbsUpButton = InlineKeyboardButton.builder().callbackData("u:" + data.id).text(data.upVotes.size() + " " + THUMBS_UP + " " + data.getUpPercent() + "%").build();
                    InlineKeyboardButton thumbsDownButton = InlineKeyboardButton.builder().callbackData("d:" + data.id).text(data.downVotes.size() + " " + THUMBS_DOWN + " " + data.getDownPercent() + "%").build();
                    InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().addRow(thumbsUpButton, thumbsDownButton).build();
                    telegramBot.editMessageReplyMarkup(((MessageCallbackQuery) event.getCallbackQuery()).getMessage(), keyboard);
                }
            }
        });
        
        firebase = new Firebase("https://hacker-news.firebaseio.com/").child("v0");
        firebase.child("topstories").addChildEventListener(new ChildEventListener() {
            public void onChildAdded(DataSnapshot dataSnapshot, String s){
                check(dataSnapshot.getValue(Long.class));
            }
        
            public void onChildChanged(DataSnapshot dataSnapshot, String s){
                check(dataSnapshot.getValue(Integer.class));
            }
            
            public void onChildRemoved(DataSnapshot dataSnapshot){}
            public void onChildMoved(DataSnapshot dataSnapshot, String s){}
            public void onCancelled(FirebaseError firebaseError){}
        });
    
        Thread thread = new Thread(() -> {
            while(true){
                try{
                    Thread.sleep(1000 * 60 * 5);
                }catch(InterruptedException e){
                    e.printStackTrace();
                    return;
                }
                
                firebase.child("topstories").addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot){
                        try{
                            ((ArrayList<Long>) dataSnapshot.getValue()).forEach(x -> check(x));
                        }catch(Exception ignored){
                        }
                    }
    
                    @Override
                    public void onCancelled(FirebaseError firebaseError){}
                });
            }
        });
        
        thread.setDaemon(true);
        thread.start();
    }
    
    public HashSet<Long> getSet(JSONArray array){
        HashSet<Long> data = new HashSet<>(array.length());
    
        for(int i = 0; i < array.length(); i++){
            data.add(array.getLong(i));
        }
        
        return data;
    }
    
    public void check(final long id){
        if(parsed.containsKey(id)){
            return;
        }
        
        firebase.child("item").child(String.valueOf(id)).addListenerForSingleValueEvent(new ValueEventListener() {
            public void onDataChange(DataSnapshot dataSnapshot){
                try{
                    HashMap value = dataSnapshot.getValue(HashMap.class);
                    if((Integer) value.get("score") > 50){
                        if(value.get("type").equals("story")){
                            parsed.put(id, new PostData(id, new HashSet<>(), new HashSet<>()));
                            save();
                            String title = (String) value.get("title");
                            Integer score = (Integer) value.get("score");
                            Date time = new java.util.Date(((Integer) value.get("time")) * 1000L);
                            String url = (String) value.get("url");
                            String user = (String) value.get("by");
                            send(id, score, user, title, time, url);
                        }
                    }
                }catch(Exception e){
                    e.printStackTrace();
                }
            }
    
            public void onCancelled(FirebaseError firebaseError){}
        });
    }
    
    private void send(long id, int score, String user, String title, Date time, String url){
        InlineKeyboardButton thumbsUpButton = InlineKeyboardButton.builder().callbackData("u:" + id).text(THUMBS_UP).build();
        InlineKeyboardButton thumbsDownButton = InlineKeyboardButton.builder().callbackData("d:" + id).text(THUMBS_DOWN).build();
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().addRow(thumbsUpButton, thumbsDownButton).build();
    
        SendableTextMessage message = SendableTextMessage.builder()
                .textBuilder()
                .bold(title).newLine()
                .plain(String.valueOf(score)).plain(", ")
                .plain(user).plain(", ")
                .plain(prettyTime.format(time)).plain(", ")
                .link("Article", url).plain(", ")
                .link("Comments", "https://news.ycombinator.com/item?id=" + id)
                .buildText()
                .replyMarkup(keyboard)
                .build();
    
        System.out.println("Sending [" + id + "] " + title);
        chat.sendMessage(message);
    }
    
    private synchronized void save(){
        JSONObject output = new JSONObject();
        
        parsed.values().forEach(data -> {
            JSONObject post = new JSONObject();
            post.put("up", data.upVotes);
            post.put("down", data.downVotes);
            output.put(String.valueOf(data.id), post);
        });
        
        try{
            Files.write(Paths.get("parsed.json"), output.toString().getBytes());
        }catch(IOException e){
            e.printStackTrace();
        }
    }
    
    public class PostData {
        
        public long id;
        public Set<Long> upVotes;
        public Set<Long> downVotes;
    
        public PostData(long id, Set<Long> upVotes, Set<Long> downVotes){
            this.id = id;
            this.upVotes = upVotes;
            this.downVotes = downVotes;
        }
    
        public int getUpPercent(){
            return (int) Math.round(((double) upVotes.size() / (double) (upVotes.size() + downVotes.size())) * 100);
        }
    
        public int getDownPercent(){
            return (int) Math.round(((double) downVotes.size() / (double) (upVotes.size() + downVotes.size())) * 100);
        }
    
        @Override
        public String toString(){
            return "PostData{" +
                    "id=" + id +
                    ", upVotes=" + upVotes +
                    ", downVotes=" + downVotes +
                    '}';
        }
    }
    
}
