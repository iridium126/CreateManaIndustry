const float CMI_STORM_CRUISE=6.,CMI_STORM_INTERCEPT=8.,CMI_COLUMN_ORBIT_SPEED=3.;
const float CMI_HELIX_THRUST_SPEED=12.,CMI_HELIX_SPEED_LIMIT=32.,CMI_HELIX_TURNS=.75;
float cmiDomeMotionLimit(uint phase,float age,float initialSpeed){
 if(phase==1u)return CMI_STORM_INTERCEPT;
 if(phase!=4u)return CMI_STORM_CRUISE;
 float blend=clamp(age/.5,0.,1.);blend=blend*blend*blend*(10.-15.*blend+6.*blend*blend);
 return max(CMI_STORM_CRUISE,mix(clamp(initialSpeed,0.,CMI_STORM_INTERCEPT),CMI_STORM_CRUISE,blend));
}

float cmiStormElapsed(float now,float then){float d=now-then;return d< -52428.8?d+104857.6:d>52428.8?d-104857.6:d;}
