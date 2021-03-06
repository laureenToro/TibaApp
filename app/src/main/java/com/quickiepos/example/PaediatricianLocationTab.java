package com.quickiepos.example;


import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.directions.route.AbstractRouting;
import com.directions.route.Route;
import com.directions.route.RouteException;
import com.directions.route.Routing;
import com.directions.route.RoutingListener;
import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PaediatricianLocationTab extends Fragment implements OnMapReadyCallback, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, com.google.android.gms.location.LocationListener, RoutingListener {

        /*-----------------------------------------------------------------------------
       |  Class: PaediatricianLocationTab
       |
       |  Purpose: A tab that initializes the mapView of the paediatrician to enable
       |           the user to respond to parent S.O.S requests
       |
       |  Note: Use of GeoFire to query and save location details to the Firebase database
       |
       |
       *---------------------------------------------------------------------------*/

    //Declare class variables
    private FusedLocationProviderClient fusedLocationProviderClient;
    private GoogleMap map;
    GoogleApiClient googleApiClient;
    Location lastLocation;
    LocationRequest locationRequest;
    MapView mMapView;
    private Button call_parent, consult_status;
    private LinearLayout parentInfo;
    private ImageView parentProfileImage;
    private TextView parentName, parentPhone, parentDestination;
    private List<Polyline> polylines;
    private int status = 0;
    private String parentId = "", destination;
    private LatLng destinationLatLng, consultLatLng;
    private Boolean isLoggingOut = false;
    private DatabaseReference parentDatabase;
    private float consultTime;
    private static final int REQUEST_CALL = 1;
    private static final int[] COLORS = new int[]{R.color.primary_dark_material_light};
    private static final String TAG = ParentLocationTab.class.getSimpleName();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.paediatrician_location_tab, container, false);

        //Initialize fragment variables
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(getActivity());
        polylines = new ArrayList<>();
        consult_status = rootView.findViewById(R.id.consultStatus);
        parentInfo = (LinearLayout) rootView.findViewById(R.id.parentInfo);
        parentName = (TextView) rootView.findViewById(R.id.parentName);
        parentPhone = (TextView) rootView.findViewById(R.id.parentPhone);

        parentDestination = (TextView) rootView.findViewById(R.id.parentdestination);
        parentProfileImage = rootView.findViewById(R.id.parentProfileimage);

        call_parent = rootView.findViewById(R.id.call);

        //Called to see if any Parent has been assigned to the Paediatrician
        getAssignedParent();

        consult_status.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch(status){
                    case 1:

                        status = 2;
                        erasePolyLines();

                        consult_status.setText("Complete Consultation");

                        break;
                    case 2:

                        recordConsult();
                        endConsult();
                        break;
                }
            }
        });


        call_parent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                makeCall();
            }
        });

        mMapView = (MapView) rootView.findViewById(R.id.map1);
        mMapView.onCreate(savedInstanceState);

        mMapView.onResume(); // needed to get the map to display immediately

        try {
            MapsInitializer.initialize(getActivity().getApplicationContext());
        } catch (Exception e) {
            e.printStackTrace();
        }

        mMapView.getMapAsync(this);

        return rootView;
    }


      /*-----------------------------------------------------------------------------------------
      |  Function(s) onMapReady, onConnected, onConnectionSuspended, onConnectionSuspended
      |
      |  Purpose:  Map functions to periodically update the user's location
      |
      |  Note:
      |	  onMapReady : Get the map ready for the fragment
      |	  onConnected : When the map is called and everything is ready to start working.
      |	  onConnectionSuspended : When the location connection has been paused
      |	  onConnectionFailed : When the location connection has failed
      |	  onLocationChanged : Periodically updates the map when a location has been changed
      |
      *-------------------------------------------------------------------------------------------*/


    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;


        try {
            // Customise the styling of the base map using a JSON object defined
            // in a raw resource file.
            boolean success = googleMap.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(
                            getActivity(), R.raw.style_json));

            if (!success) {
                Log.e(TAG, "Style parsing failed.");
            }
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Can't find style. Error: ", e);
        }
        if (ActivityCompat.checkSelfPermission(getActivity(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(getActivity(), android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        buildGoogleApiClient();
        map.setMyLocationEnabled(true);
    }

    protected synchronized void buildGoogleApiClient(){
        googleApiClient = new GoogleApiClient.Builder(getActivity())
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        googleApiClient.connect();
    }

    @Override
    public void onLocationChanged(Location location) {
        if(getActivity()!=null){

            if(!parentId.equals("") && this.lastLocation !=null && location != null){
                consultTime += this.lastLocation.distanceTo(location)/1000;
            }
            this.lastLocation = location;
            LatLng latLng = new LatLng(location.getLatitude(),location.getLongitude());
            map.moveCamera(CameraUpdateFactory.newLatLng(latLng));

            map.animateCamera(CameraUpdateFactory.zoomTo(16));

            String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            DatabaseReference refAvailable = FirebaseDatabase.getInstance().getReference("paediatriciansAvailable");
            DatabaseReference refWorking = FirebaseDatabase.getInstance().getReference("paediatriciansWorking");
            GeoFire geoFireAvailable = new GeoFire(refAvailable);
            GeoFire geoFireWorking = new GeoFire(refWorking);

            switch (parentId){
                case "":
                    geoFireWorking.removeLocation(userId);
                    geoFireAvailable.setLocation(userId, new GeoLocation(location.getLatitude(), location.getLongitude()));
                    break;

                default:
                    geoFireAvailable.removeLocation(userId);
                    geoFireWorking.setLocation(userId, new GeoLocation(location.getLatitude(), location.getLongitude()));
                    break;
            }

        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        locationRequest = new LocationRequest();
        locationRequest.setInterval(1000);
        locationRequest.setFastestInterval(1000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        if (ActivityCompat.checkSelfPermission(getActivity(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(getActivity(), android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    }

    LocationCallback mLocationCallback = new LocationCallback(){
        @Override
        public void onLocationResult(LocationResult locationResult) {
            for(Location location : locationResult.getLocations()){
                if(getActivity()!=null){

                    if(!parentId.equals("") && PaediatricianLocationTab.this.lastLocation !=null && location != null){
                        consultTime += PaediatricianLocationTab.this.lastLocation.distanceTo(location)/1000;
                    }
                    PaediatricianLocationTab.this.lastLocation = location;


                    LatLng latLng = new LatLng(location.getLatitude(),location.getLongitude());
                    map.moveCamera(CameraUpdateFactory.newLatLng(latLng));
                    map.animateCamera(CameraUpdateFactory.zoomTo(11));

                    String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
                    DatabaseReference refAvailable = FirebaseDatabase.getInstance().getReference("paediatriciansAvailable");
                    DatabaseReference refWorking = FirebaseDatabase.getInstance().getReference("paediatriciansWorking");
                    GeoFire geoFireAvailable = new GeoFire(refAvailable);
                    GeoFire geoFireWorking = new GeoFire(refWorking);

                    switch (parentId){
                        case "":
                            geoFireWorking.removeLocation(userId);
                            geoFireAvailable.setLocation(userId, new GeoLocation(location.getLatitude(), location.getLongitude()));
                            break;

                        default:
                            geoFireAvailable.removeLocation(userId);
                            geoFireWorking.setLocation(userId, new GeoLocation(location.getLatitude(), location.getLongitude()));
                            break;
                    }
                }
            }
        }
    };


   /*------------------------------------------------------------------
   |  Function: disconnectPaediatrician
   |
   |  Purpose:  Remove Paediatrician from paediatriciansAvailable table
   |            if the user is not active in the Application
   |
   |  Note: Application only considers paediatricians active in the application as available
   |
   |
   *-------------------------------------------------------------------*/

    private void disconnectPaediatrician(){
        if(fusedLocationProviderClient != null){
            fusedLocationProviderClient.removeLocationUpdates(mLocationCallback);
        }
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("paediatriciansAvailable");

        GeoFire geoFire = new GeoFire(ref);
        geoFire.removeLocation(userId);
    }

    /*--------------------------------------------------------------------------------------------------------
    |  Function(s) getAssignedParent, getAssignedParentConsultLocation, getAssignedParentInfo,
    |
    |  Purpose: Assign a parent to a paediatrician
    |
    |  Note:
    |	  getAssignedParent : Assigns a parent request to the closest paediatrician
    |	  getAssignedParentConsultLocation : Assigns a consultation to the paediatrician with the parent request
    |	  getAssignedParentInfo : Gives the paediatrician the parent information
    |
    |
    *-----------------------------------------------------------------------------------------------------------*/

    private void getAssignedParent(){
        String paediatricianId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference assignedParentRef = FirebaseDatabase.getInstance().getReference().child("Users").child("paediatricians").child(paediatricianId).child("parentRequest").child("parentConsultId");
        assignedParentRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if(dataSnapshot.exists()){
                    status = 1;
                    parentId = dataSnapshot.getValue().toString();
                    getAssignedParentConsultLocation();
                    getAssignedParentInfo();
                }else{


                    endConsult();

                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
    }


    Marker consultMarker;
    private DatabaseReference assignedParentConsultLocationRef;
    private ValueEventListener assignedParentConsultLocationRefListener;

    private void getAssignedParentConsultLocation(){
        assignedParentConsultLocationRef = FirebaseDatabase.getInstance().getReference().child("parentRequest").child(parentId).child("l");
        assignedParentConsultLocationRefListener = assignedParentConsultLocationRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if(dataSnapshot.exists() && !parentId.equals("")){
                    List<Object> map = (List<Object>) dataSnapshot.getValue();
                    double locationLat = 0;
                    double locationLng = 0;
                    if(map.get(0) != null){
                        locationLat = Double.parseDouble(map.get(0).toString());
                    }
                    if(map.get(1) != null){
                        locationLng = Double.parseDouble(map.get(1).toString());
                    }
                    LatLng consultLatLng = new LatLng(locationLat,locationLng);
                    consultMarker = PaediatricianLocationTab.this.map.addMarker(new MarkerOptions().position(consultLatLng).title("Consultation last location").icon(BitmapDescriptorFactory.fromResource(R.mipmap.pickup_marker)));
                    getRouteToMarker(consultLatLng);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
    }

    private void getAssignedParentInfo(){
        parentInfo.setVisibility(View.VISIBLE);
        call_parent.setVisibility(View.VISIBLE);
        parentDatabase = FirebaseDatabase.getInstance().getReference().child("Users").child("parents").child(parentId);
        parentDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists() && dataSnapshot.getChildrenCount()>0){

                    Map<String, Object> map = (Map<String, Object>) dataSnapshot.getValue();

                    if (map.get("name")!= null){
                        parentName.setText(map.get("name").toString());

                    }
                    if (map.get("phone")!= null){

                        parentPhone.setText(map.get("phone").toString());
                    }
                    if (map.get("photoUrl") != null){


                        Glide.with(getActivity()).load(map.get("photoUrl").toString()).into(parentProfileImage);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {

            }
        });

    }


     /*------------------------------------------------------------------
        |  Function: makeCall
        |
        |  Purpose:  Enables paediatrician to make a call when assigned the parent phone number
        |
        |
        *-------------------------------------------------------------------*/

    private void makeCall(){
        String number = parentPhone.getText().toString();
        if (number.trim().length() > 0) {

            if (ContextCompat.checkSelfPermission(getActivity(),
                    Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL);
            } else {
                String dial = "tel:" + number;
                startActivity(new Intent(Intent.ACTION_CALL, Uri.parse(dial)));
            }

        } else {
            Toast.makeText(getActivity(), "Phone number not provided", Toast.LENGTH_SHORT).show();
        }
    }

   /*------------------------------------------------------------------
   |  Function(s) checkLocationPermission, onRequestPermissionsResult
   |
   |  Purpose: This methods check and request permissions required for the application to run
   |
   |  Note:
   |	  checkLocationPermission : Checks if the location permission has been granted for location
   |	  onRequestPermissionsResult : requests for permission for making a phone call
   |
   |
   *-------------------------------------------------------------------*/

    private void checkLocationPermission() {
        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
            if (ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION)) {
                new AlertDialog.Builder(getActivity())
                        .setTitle("give permission")
                        .setMessage("give permission message")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                            }
                        })
                        .create()
                        .show();
            }
            else{
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CALL) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                makeCall();
            } else {
                Toast.makeText(getActivity(), "Permission DENIED", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /*------------------------------------------------------------------
    |  Function(s) getRouteToMarker, onRoutingFailure, onRoutingStart, onRoutingSuccess
    |
    |  Purpose:  Draw the route between the parent and paediatrician
    |
    |  Note:
    |	  getRouteToMarker : get the route to marker
    |	  onRoutingFailure : Responds when routing fails
    |	  onRoutingStart : When routing starts
    |	  onRoutingSuccess : When routing was successful
    |
    *-------------------------------------------------------------------*/
    private void getRouteToMarker(LatLng consultLatLng) {

          Routing routing = new Routing.Builder()
                  .key("AIzaSyDJRGrs2-HETD0U3khyb2lAf3axA-CBl9I")
                  .travelMode(AbstractRouting.TravelMode.DRIVING)
                  .withListener(this)
                  .alternativeRoutes(false)
                  .waypoints(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()), consultLatLng)
                  .build();
          routing.execute();

    }

    @Override
    public void onRoutingFailure(RouteException e) {

        if(e != null) {
            Toast.makeText(getActivity(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }else {
            Toast.makeText(getActivity(), "Something went wrong, Try again", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRoutingStart() {

    }

    @Override
    public void onRoutingSuccess(ArrayList<Route> route, int shortestRouteIndex) {

        if(polylines.size()>0) {
            for (Polyline poly : polylines) {
                poly.remove();
            }
        }

        polylines = new ArrayList<>();
        //add route(s) to the map.
        for (int i = 0; i <route.size(); i++) {

            //In case of more than 5 alternative routes
            int colorIndex = i % COLORS.length;

            PolylineOptions polyOptions = new PolylineOptions();
            polyOptions.color(getResources().getColor(COLORS[colorIndex]));
            polyOptions.width(10 + i * 3);
            polyOptions.addAll(route.get(i).getPoints());
            Polyline polyline = map.addPolyline(polyOptions);
            polylines.add(polyline);

            Toast.makeText(getActivity(),"Route "+ (i+1) +": distance - "+ route.get(i).getDistanceValue()+": duration - "+ route.get(i).getDurationValue(),Toast.LENGTH_SHORT).show();
        }

    }

    @Override
    public void onRoutingCancelled() {

    }

    private void erasePolyLines(){
        for (Polyline line: polylines){
            line.remove();
        }
        polylines.clear();
    }

    /*------------------------------------------------------------------
    |  Function(s) endConsult, recordConsult
    |
    |  Purpose:  Record and end consultations
    |
    *-------------------------------------------------------------------*/

    private  void endConsult(){

        consult_status.setText("pick parent");
        erasePolyLines();
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();


        DatabaseReference paediatricianRef = FirebaseDatabase.getInstance().getReference().child("Users").child("paediatricians").child(userId).child("parentRequest");
        paediatricianRef.removeValue();

        consultTime = 0;

        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("parentRequest");
        GeoFire geoFire = new GeoFire(ref);
        geoFire.removeLocation(parentId);

        parentId = "";

        if(consultMarker != null){
            consultMarker.remove();
        }
        if (assignedParentConsultLocationRefListener != null){
            assignedParentConsultLocationRef.removeEventListener(assignedParentConsultLocationRefListener);
        }

        parentInfo.setVisibility(View.GONE);

        call_parent.setVisibility(View.GONE);
        parentPhone.setText("");
        parentName.setText("");
        parentProfileImage.setImageResource(R.mipmap.user_image);


    }

    private void recordConsult(){
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DatabaseReference parentRef = FirebaseDatabase.getInstance().getReference().child("Users").child("parents").child(parentId).child("history");

        DatabaseReference paediatricianRef = FirebaseDatabase.getInstance().getReference().child("Users").child("paediatricians").child(userId).child("history");

        DatabaseReference historyRef = FirebaseDatabase.getInstance().getReference().child("history");

        String requestId = historyRef.push().getKey();

        paediatricianRef.child(requestId).setValue(true);
        parentRef.child(requestId).setValue(true);

        HashMap map = new HashMap();
        map.put("paediatrician", userId);
        map.put("parent", parentId);
        map.put("rating", 0);
        map.put("consultTime", consultTime);
        map.put("timestamp", getCurrentTimestamp());

        historyRef.child(requestId).updateChildren(map);

    }
    private Long getCurrentTimestamp() {
        Long timestamp = System.currentTimeMillis()/1000;
        return timestamp;
    }

    /*------------------------------------------------------------------
    |  Function(s) onResume, onPause, onDestroy, onLowMemory
    |
    |  Purpose:  Adapt the mapView with changes that are going on with the fragment using the main
    |            fragment functions.
    |
    |  Note:
    |	  onResume : When the fragment has been resumed resume MapView
    |	  onPause : When the fragment has been pause pause the MapView
    |	  onDestroy : When the fragment has been destroyed also destroy the MapView
    |	  onLowMemory : When the phone is on low memory change the MapView to be more battery conscious
    |	  onStop : When the user gets out of the activity the paediatrician is considered unavailable
    |
    |
    *-------------------------------------------------------------------*/

    @Override
    public void onStop() {
        super.onStop();
        if (!isLoggingOut){

            disconnectPaediatrician();
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mMapView.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mMapView.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mMapView.onLowMemory();
    }


}
