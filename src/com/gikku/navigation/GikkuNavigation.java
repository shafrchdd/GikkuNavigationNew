package com.gikku.navigation;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@DesignerComponent(version=1,description="GPS navigation with Google Places and Routes APIs",category=ComponentCategory.EXTENSION,nonVisible=true,iconName="")
@SimpleObject(external=true)
@UsesPermissions(permissionNames="android.permission.INTERNET,android.permission.ACCESS_FINE_LOCATION,android.permission.ACCESS_COARSE_LOCATION")
public class GikkuNavigation extends AndroidNonvisibleComponent implements LocationListener,OnDestroyListener {
  private final Activity activity;
  private final LocationManager manager;
  private final Handler ui=new Handler(Looper.getMainLooper());
  private final ExecutorService pool=Executors.newFixedThreadPool(2);
  private final AtomicBoolean calculating=new AtomicBoolean(false);
  private final ArrayList<String> stops=new ArrayList<>();
  private volatile String key="",destination="";
  private volatile Location location;
  private volatile boolean tracking=false;
  public GikkuNavigation(ComponentContainer container) {
    super(container.$form()); activity=container.$context();
    manager=(LocationManager)activity.getSystemService(Context.LOCATION_SERVICE);
    container.$form().registerForOnDestroy(this);
  }
  private void emit(String code,String message) { ui.post(new Runnable(){@Override public void run(){NavigationError(code,message);}}); }
  @SimpleEvent public void NavigationError(String code,String message){EventDispatcher.dispatchEvent(this,"NavigationError",code,message);}
  @SimpleEvent public void SearchResults(String json){EventDispatcher.dispatchEvent(this,"SearchResults",json);}
  @SimpleEvent public void DestinationSet(String value){EventDispatcher.dispatchEvent(this,"DestinationSet",value);}
  @SimpleEvent public void StopsChanged(String json){EventDispatcher.dispatchEvent(this,"StopsChanged",json);}
  @SimpleEvent public void LocationUpdated(double lat,double lng,double speedKmh,double accuracyMeters){EventDispatcher.dispatchEvent(this,"LocationUpdated",lat,lng,speedKmh,accuracyMeters);}
  @SimpleEvent public void RouteReady(String json){EventDispatcher.dispatchEvent(this,"RouteReady",json);}
  @SimpleFunction public void SetApiKey(String apiKey){if(apiKey==null||apiKey.trim().isEmpty()){emit("API_KEY_MISSING","An API key is required.");return;}key=apiKey.trim();}
  @SimpleFunction public String GetDestination(){return destination;}
  @SimpleFunction public String GetStops(){synchronized(stops){return new JSONArray(stops).toString();}}
  private JSONObject waypoint(String input) throws JSONException {
    String s=input.trim();
    if(s.isEmpty())throw new IllegalArgumentException("A place, Place ID or coordinates are required.");
    if(s.contains(",")) {
      String[] p=s.split(",",-1);
      if(p.length==2) {
        try {
          double lat=Double.parseDouble(p[0].trim()),lon=Double.parseDouble(p[1].trim());
          if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat>90||lon < -180||lon>180)throw new IllegalArgumentException("Coordinates are out of range.");
          return new JSONObject().put("location",new JSONObject().put("latLng",new JSONObject().put("latitude",lat).put("longitude",lon)));
        }catch(NumberFormatException ex){
          if(p[0].trim().matches("[+-]?[0-9.]+")||p[1].trim().matches("[+-]?[0-9.]+"))throw new IllegalArgumentException("Invalid coordinate pair.");
        }
      }
    }
    if(s.startsWith("ChI"))return new JSONObject().put("placeId",s);
    return new JSONObject().put("address",s);
  }
  private boolean valid(String s){
    try{waypoint(s);return true;}
    catch(Exception ex){emit("INVALID_COORDINATES",ex.getMessage());return false;}
  }
  @SimpleFunction public void SetDestination(String value){
    if(value==null||!valid(value))return;
    destination=value.trim();ui.post(new Runnable(){@Override public void run(){DestinationSet(destination);}});
  }
  private void changed(){String s=GetStops();ui.post(new Runnable(){@Override public void run(){StopsChanged(s);}});}
  @SimpleFunction public void AddStop(String value){if(value==null||!valid(value))return;synchronized(stops){stops.add(value.trim());}changed();}
  @SimpleFunction public void RemoveStop(int index){synchronized(stops){if(index<1||index>stops.size()){emit("INVALID_STOP_INDEX","Stop index is outside the list.");return;}stops.remove(index-1);}changed();}
  @SimpleFunction public void ClearStops(){synchronized(stops){stops.clear();}changed();}
  @SimpleFunction public double GetLatitude(){Location p=location;return p==null?Double.NaN:p.getLatitude();}
  @SimpleFunction public double GetLongitude(){Location p=location;return p==null?Double.NaN:p.getLongitude();}
  @SimpleFunction public double GetSpeed(){Location p=location;return p==null?Double.NaN:(p.hasSpeed()?p.getSpeed()*3.6:0);}
  @SimpleFunction public void StartLocation(){
    if(Build.VERSION.SDK_INT>=23&&activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},7011);
      emit("GPS_PERMISSION_DENIED","Grant precise location permission, then call StartLocation again.");return;
    }
    try {
      if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){emit("GPS_UNAVAILABLE","Enable GPS in device settings.");return;}
      manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,Looper.getMainLooper());tracking=true;
    }catch(SecurityException ex){emit("GPS_PERMISSION_DENIED","Precise location permission is required.");}
    catch(Exception ex){emit("GPS_UNAVAILABLE","Cannot start GPS tracking.");}
  }
  @SimpleFunction public void StopLocation(){tracking=false;try{manager.removeUpdates(this);}catch(Exception ignored){}}
  @Override public void onLocationChanged(Location p){if(!tracking||p==null)return;location=p;LocationUpdated(p.getLatitude(),p.getLongitude(),p.hasSpeed()?p.getSpeed()*3.6:0,p.hasAccuracy()?p.getAccuracy():-1);}
  @Override public void onProviderDisabled(String provider){if(LocationManager.GPS_PROVIDER.equals(provider))emit("GPS_UNAVAILABLE","GPS was disabled.");}
  @Override public void onProviderEnabled(String provider){}
  @Override public void onStatusChanged(String provider,int status,Bundle extras){}
  @Override public void onDestroy(){StopLocation();pool.shutdownNow();}
  private JSONObject post(String url,JSONObject request,String fieldMask) throws Exception{
    HttpURLConnection c=null;
    try{
      c=(HttpURLConnection)new URL(url).openConnection();
      c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestMethod("POST");c.setDoOutput(true);
      c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("X-Goog-Api-Key",key);c.setRequestProperty("X-Goog-FieldMask",fieldMask);
      byte[] b=request.toString().getBytes(StandardCharsets.UTF_8);
      try(OutputStream out=c.getOutputStream()){out.write(b);}
      int status=c.getResponseCode();InputStream stream=status<400?c.getInputStream():c.getErrorStream();
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(stream!=null){try(InputStream in=stream){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){bytes.write(buf,0,n);}}}
      JSONObject result=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
      if(status>=400){JSONObject error=result.optJSONObject("error");throw new ApiException("HTTP "+status+": "+(error==null?"Request rejected":error.optString("message","Request rejected")));}
      return result;
    }finally{if(c!=null)c.disconnect();}
  }
  private static class ApiException extends Exception{ApiException(String s){super(s);}}
  private void fail(Exception ex){
    if(ex instanceof ApiException)emit("API_ERROR",ex.getMessage());
    else if(ex instanceof IOException)emit("NETWORK_ERROR","Network request failed: "+ex.getClass().getSimpleName());
    else emit("INVALID_RESPONSE","Unexpected API response: "+ex.getClass().getSimpleName());
  }
  @SimpleFunction public void SearchPlace(String query){
    if(key.isEmpty()){emit("API_KEY_MISSING","SetApiKey must be called first.");return;}
    if(query==null||query.trim().isEmpty()){emit("INVALID_RESPONSE","Search query cannot be empty.");return;}
    final String q=query.trim();
    pool.execute(new Runnable(){@Override public void run(){
      try{
        JSONObject result=post("https://places.googleapis.com/v1/places:searchText",new JSONObject().put("textQuery",q).put("pageSize",20),"places.displayName,places.formattedAddress,places.id,places.location");
        JSONArray places=result.optJSONArray("places"),out=new JSONArray();
        if(places!=null)for(int i=0;i<places.length();i++){
          JSONObject p=places.getJSONObject(i),loc=p.optJSONObject("location"),name=p.optJSONObject("displayName");
          out.put(new JSONObject().put("name",name==null?"":name.optString("text","")).put("address",p.optString("formattedAddress","")).put("placeId",p.optString("id","")).put("latitude",loc==null?JSONObject.NULL:loc.opt("latitude")).put("longitude",loc==null?JSONObject.NULL:loc.opt("longitude")));
        }
        String json=out.toString();ui.post(new Runnable(){@Override public void run(){SearchResults(json);}});
      }catch(Exception ex){fail(ex);}
    }});
  }
  private static double seconds(String s){
    if(s==null||!s.endsWith("s"))return 0;
    try{return Double.parseDouble(s.substring(0,s.length()-1));}catch(NumberFormatException ex){return 0;}
  }
  private static double lat(JSONObject loc){JSONObject v=loc==null?null:loc.optJSONObject("latLng");return v==null?0:v.optDouble("latitude",0);}
  private static double lon(JSONObject loc){JSONObject v=loc==null?null:loc.optJSONObject("latLng");return v==null?0:v.optDouble("longitude",0);}
  @SimpleFunction public void CalculateRoute(){
    if(!calculating.compareAndSet(false,true)){emit("API_ERROR","A route request is already in progress.");return;}
    if(key.isEmpty()){calculating.set(false);emit("API_KEY_MISSING","SetApiKey must be called first.");return;}
    Location here=location;
    if(here==null){calculating.set(false);emit("LOCATION_NOT_READY","Wait for the first GPS fix.");return;}
    String dest=destination; if(dest.isEmpty()){calculating.set(false);emit("DESTINATION_MISSING","Set a destination first.");return;}
    final ArrayList<String> ordered; synchronized(stops){ordered=new ArrayList<>(stops);}
    pool.execute(()->{
      try{
        JSONObject origin=new JSONObject().put("location",new JSONObject().put("latLng",new JSONObject().put("latitude",here.getLatitude()).put("longitude",here.getLongitude())));
        JSONArray intermediate=new JSONArray();for(String stop:ordered)intermediate.put(waypoint(stop));
        JSONObject body=new JSONObject().put("origin",origin).put("destination",waypoint(dest)).put("intermediates",intermediate)
          .put("travelMode","DRIVE").put("routingPreference","TRAFFIC_AWARE").put("polylineQuality","HIGH_QUALITY")
          .put("languageCode","en").put("units","METRIC");
        JSONObject raw=post("https://routes.googleapis.com/directions/v2:computeRoutes",body,"routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline,routes.legs.distanceMeters,routes.legs.duration,routes.legs.steps.distanceMeters,routes.legs.steps.navigationInstruction.instructions,routes.legs.steps.startLocation,routes.legs.steps.endLocation");
        JSONArray routes=raw.optJSONArray("routes");
        if(routes==null||routes.length()==0){emit("ROUTE_NOT_FOUND","No driving route could be found.");return;}
        JSONObject route=routes.getJSONObject(0),out=new JSONObject();
        out.put("distanceMeters",route.optLong("distanceMeters",0)).put("durationSeconds",seconds(route.optString("duration","0s")));
        JSONObject poly=route.optJSONObject("polyline");out.put("encodedPolyline",poly==null?"":poly.optString("encodedPolyline",""));
        JSONArray legs=route.optJSONArray("legs"),outLegs=new JSONArray();
        if(legs!=null)for(int i=0;i<legs.length();i++){
          JSONObject leg=legs.getJSONObject(i),ol=new JSONObject();
          ol.put("distanceMeters",leg.optLong("distanceMeters",0)).put("durationSeconds",seconds(leg.optString("duration","0s")));
          JSONArray steps=leg.optJSONArray("steps"),outSteps=new JSONArray();
          if(steps!=null)for(int j=0;j<steps.length();j++){
            JSONObject step=steps.getJSONObject(j),nav=step.optJSONObject("navigationInstruction");
            outSteps.put(new JSONObject().put("instruction",nav==null?"":nav.optString("instructions",""))
              .put("distanceMeters",step.optLong("distanceMeters",0))
              .put("startLatitude",lat(step.optJSONObject("startLocation"))).put("startLongitude",lon(step.optJSONObject("startLocation")))
              .put("endLatitude",lat(step.optJSONObject("endLocation"))).put("endLongitude",lon(step.optJSONObject("endLocation"))));
          }
          ol.put("steps",outSteps);outLegs.put(ol);
        }
        out.put("legs",outLegs);String json=out.toString();ui.post(new Runnable(){@Override public void run(){RouteReady(json);}});
      }catch(Exception ex){fail(ex);}
      finally{calculating.set(false);}
    }});
  }
}
