import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeMap;

/**
 * Created by laura-jane on 2/7/18.
 */

class Vehicle {

    private int VEHICLE_ID;
    private LocationPoint DEPOT_LOCATION;
    private int MAX_ROUTE_DURATION; // the maximum route duration
    private int MAX_SEATS; // defines the max number of requests the vehicle can hold
    private double SPEED; // speed should be in distance per min
    private int[] WEIGHTS = {0,0,0,0};

    private ArrayList<Request> requests = new ArrayList<>(); // a list to hold the requests
    private ArrayList<Request> servicedRequests = new ArrayList<>();
    private ArrayList<Request> inTransitRequests = new ArrayList<>();
    private ArrayList<Request> unservicedRequests = new ArrayList<>();

    Route route = new Route();
    private LocationPoint location;

    Vehicle (int VEHICLE_ID, Defaults defaults){
        this.VEHICLE_ID = VEHICLE_ID;
        this.MAX_SEATS = defaults.getMaxSeats();
        this.MAX_ROUTE_DURATION = defaults.getMaxRouteLength();
        this.SPEED = defaults.getVehicleDefaultSpeed();
        this.DEPOT_LOCATION = defaults.getDepotLocation();

        this.WEIGHTS[0] = defaults.getDriveTimeWeight();
        this.WEIGHTS[1] = defaults.getRouteDurationWeight();
        this.WEIGHTS[2] = defaults.getPackageRideTimeViolationWeight();
        this.WEIGHTS[3] = defaults.getRouteDurationWeight();

        location = DEPOT_LOCATION; // starting location is the depot
    }

    // get methods
    public int getVehicleID(){ return VEHICLE_ID; }
    public int getMaxSeats(){ return MAX_SEATS; }
    public int getMaxRouteDuration(){ return MAX_ROUTE_DURATION; }

    // set methods
    public void setVehicleID( int VEHICLE_ID ){ this.VEHICLE_ID = VEHICLE_ID; }
    public void setMaxSeats(int MAX_SEATS){
        this.MAX_SEATS = MAX_SEATS;
    }
    public void setMaxDuration(int MAX_ROUTE_DURATION){
        this.MAX_ROUTE_DURATION = MAX_ROUTE_DURATION;
    }

    public void addRequest(Request request){
        requests.add(request);
        unservicedRequests.add(request);
    }

    public void removeRequest(Request request, LocalDateTime time){
        if (unservicedRequests.contains(request)){
            unservicedRequests.remove(request);
            requests.remove(request);
        }
        else {
            System.out.println("Error: Request " + request.getRequestNum() + " could not be removed from " +  this.VEHICLE_ID +
                    "at time " + formatTimeStamp(time) + ". It was not on the list of unserviced requests for this vehicle." );
            // time should be changed to LocalDateTime.now() for real world applications
        }
    }

    public void pickUpRequest(Request request, LocalDateTime time){
        if (unservicedRequests.contains(request)){
            unservicedRequests.remove(request);
            inTransitRequests.add(request);
        }
        else {
            System.out.println("Error: Request " + request.getRequestNum() + " was not picked up at time " + formatTimeStamp(time) +
                    ". It was not on the list of unserviced requests for vehicle " + this.VEHICLE_ID + ".");
            // time should be changed to LocalDateTime.now() for real world applications
        }
    }

    public void dropOffRequest(Request request, LocalDateTime time){
        if (inTransitRequests.contains(request)){
            inTransitRequests.remove(request);
            servicedRequests.add(request);
        }
        else {
            System.out.println("Error: Request " + request.getRequestNum() + " was not dropped off at time " + formatTimeStamp(time) +
                    ". It was not on the list of in transit requests for vehicle " + this.VEHICLE_ID + ".");
            // time should be changed to LocalDateTime.now() for real world applications
        }
    }

    private String formatTimeStamp(LocalDateTime time){
        return time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    // Each vehicle has a route
    class Route {

        double routeDuration = 0;
        LocalDateTime routeStartTime;
        Request firstRequest;

        LocalDateTime currentTime;
        LocationPoint currentLocation;
        LocationPoint nextLocation;

        ArrayList<Request> unscheduledRequests;
        ArrayList<Request> scheduledPickUps;
        ArrayList<Request> scheduledDropoffs;

        void setRoute(LocalDateTime now){

            currentTime = now; // = LocalDateTime.now(); in a real world application, for this program, time is given by the handler
            currentLocation = location;

            unscheduledRequests = unservicedRequests;
            scheduledPickUps = inTransitRequests;
            scheduledDropoffs = servicedRequests;

            // If no requests have been picked up or serviced, find the first request for the day, based on earliest pickup
            if(scheduledPickUps.isEmpty() && scheduledDropoffs.isEmpty()){

                LocalDateTime earliestPickup = LocalDateTime.MAX; // latest date and time supported by LocalDateTime

                for (Request request: unscheduledRequests){
                    if(earliestPickup.isAfter(request.getPickUpTime())){
                        earliestPickup = request.getPickUpTime();
                        firstRequest = request;
                    }
                }

                routeStartTime = currentTime;
                nextLocation = firstRequest.getPickUpLoc();

                currentTime.plusSeconds((long)DEPOT_LOCATION.timeTo(nextLocation, SPEED));
                currentLocation = nextLocation;

                schedulePickUp(firstRequest, currentTime);

            }

            // while there are still requests to schedule

            while (!unscheduledRequests.isEmpty() && !scheduledPickUps.isEmpty()){

                TreeMap<Double, Request> nearestFour = getNearestFour(currentLocation, currentTime);
                Request nextRequest = findCheapestMove(nearestFour.values());

                // if statement determines if the pick up or drop off location should be used
                if (unscheduledRequests.contains(nextRequest)){
                    nextLocation = nextRequest.getPickUpLoc();
                    currentTime.plusSeconds((long)currentLocation.timeTo(nextLocation, SPEED));
                    schedulePickUp(nextRequest, currentTime);
                }
                else if (inTransitRequests.contains(nextRequest)){
                    nextLocation = nextRequest.getDropOffLoc();
                    currentTime.plusSeconds((long)currentLocation.timeTo(nextLocation, SPEED));
                    scheduleDropOff(nextRequest, currentTime);
                } else {
                    System.out.println("Error: Next Location was not set at time " + formatTimeStamp(currentTime) + " for Request "
                            + nextRequest.getRequestNum() + "." );
                }

                currentLocation = nextLocation;
            }

            routeDuration = ChronoUnit.MINUTES.between(routeStartTime, currentTime);

        }


        // separation calculation with weights to reduce separation when time window violations are occurring
        double getSeparation(LocationPoint startingPoint, LocationPoint endingPoint,
                             LocalDateTime currentTime, LocalDateTime dropOffTime){

            double separation;
            double travelTime;
            LocalDateTime arrivalTime;

            travelTime = startingPoint.timeTo(endingPoint, SPEED); // add time
            arrivalTime = currentTime.plusSeconds((long)travelTime);

            if(arrivalTime.isAfter(dropOffTime)){
                // violation case
                separation = WEIGHTS[0] * travelTime - WEIGHTS[2] * (ChronoUnit.MINUTES.between(arrivalTime, dropOffTime));
            }
            else {
                // non-violation case. Packages which have more time until drop off are given lower priority
                separation = WEIGHTS[0] * travelTime - ChronoUnit.MINUTES.between(arrivalTime, dropOffTime);
            }

            return separation;
        }

        // separation calculation with weights to reduce separation when time window violations are occurring
        double getCost(LocationPoint startingPoint, LocationPoint endingPoint,
                             LocalDateTime currentTime, LocalDateTime dropOffTime){

            double cost;
            double travelTime;
            LocalDateTime arrivalTime;

            travelTime = startingPoint.timeTo(endingPoint, SPEED); // add time
            arrivalTime = currentTime.plusSeconds((long)travelTime);

            if(arrivalTime.isAfter(dropOffTime)){
                // violation case
                cost = WEIGHTS[0] * travelTime + WEIGHTS[2] * (ChronoUnit.MINUTES.between(arrivalTime, dropOffTime));
            }
            else {
                // non-violation case
                cost = WEIGHTS[0] * travelTime - ChronoUnit.MINUTES.between(arrivalTime, dropOffTime);
            }

            return cost;
        }


        TreeMap<Double, Request> getNearestFour(LocationPoint currentLocation, LocalDateTime currentTime,
                                                ArrayList<Request> requestsToPickUp, ArrayList<Request> requestsToDropOff){

            TreeMap<Double, Request> nearestFour = new TreeMap<>();
            TreeMap<Double, Request> separationTree = new TreeMap<>();

            for (Request dropoff : requestsToDropOff){
                // check possible drop off locations first
                separationTree.put(
                        getSeparation(currentLocation, dropoff.getDropOffLoc(), currentTime, dropoff.getDropOffTime()), dropoff);
            }

            // the number of requests en route cannot exceed the vehicle's available space
            if (requestsToPickUp.size() < MAX_SEATS){
                for (Request pickup : requestsToPickUp){
                    separationTree.put(
                            getSeparation(currentLocation, pickup.getPickUpLoc(), currentTime, pickup.getDropOffTime()), pickup);
                }
            }

            for (int i = 0; i < 4; i++){

                if(separationTree.isEmpty()){ break; }

                Double nearest = separationTree.firstKey();
                nearestFour.put(nearest, separationTree.get(nearest));
                separationTree.remove(nearest);
            }

            return nearestFour;
        }

        TreeMap<Double, Request> getNearestFour(LocationPoint currentLocation, LocalDateTime currentTime){
            // Overloaded method with default values for optional inputs
            return getNearestFour(currentLocation, currentTime, unscheduledRequests, scheduledPickUps);
        }

        Request findCheapestMove(Collection<Request> nearestFour){
            TreeMap<Double, Request> moveCosts = new TreeMap<>();
            // establish local variables

            for (Request request: nearestFour){

                LocalDateTime time = currentTime;
                LocationPoint location = currentLocation; // default to avoid null error
                LocationPoint nextLocation;

                ArrayList<Request> requestsToPickUp = unscheduledRequests;
                ArrayList<Request> requestsToDropOff = scheduledPickUps;
                ArrayList<Request> dropOffs = scheduledDropoffs;

                double cost = 0;

                // if statement determines if the pick up or drop off location should be used
                if (requestsToPickUp.contains(request)){
                    nextLocation = request.getPickUpLoc();

                    requestsToPickUp.remove(request);
                    requestsToDropOff.add(request);

                }
                else {
                    nextLocation = request.getDropOffLoc();

                    requestsToDropOff.remove(request);
                    dropOffs.add(request);
                }

                // add separation to cost
                cost += getSeparation(location, nextLocation, time, request.getDropOffTime());

                time.plusSeconds((long)location.timeTo(nextLocation, SPEED));


                // calculate the cost for the 3 next nearest neighbor moves. Add to cost.
                for (int i = 0; i<3; i++){

                    if(requestsToPickUp.isEmpty() && requestsToDropOff.isEmpty()){ break; }

                    // find nearest neighbor
                    TreeMap<Double, Request> separationTree = getNearestFour(location, time, requestsToPickUp, requestsToDropOff);
                    request = separationTree.firstEntry().getValue();

                    if (requestsToPickUp.contains(request)){
                        requestsToPickUp.remove(request);
                        requestsToDropOff.add(request);

                        nextLocation = request.getPickUpLoc();
                    }
                    else if (requestsToDropOff.contains(request)){
                        requestsToDropOff.remove(request);
                        dropOffs.add(request);

                        nextLocation = request.getDropOffLoc();
                    }

                    time.plusSeconds((long)location.timeTo(request.getPickUpLoc(), SPEED));
                    cost += getCost(location, nextLocation, time, request.getDropOffTime());

                }

                moveCosts.put(cost, request);
            }

            return moveCosts.firstEntry().getValue();
        }

        void schedulePickUp(Request request, LocalDateTime time){
            if (unscheduledRequests.contains(request)){
                unscheduledRequests.remove(request);
                scheduledPickUps.add(request);
                request.setScheduledPickUp(time);
            }
            else {
                System.out.println("You cannot schedule a pick up for this request. It is not on the list of unscheduled requests.");
            }
        }

        void scheduleDropOff(Request request, LocalDateTime time){
            if (inTransitRequests.contains(request)){
                inTransitRequests.remove(request);
                servicedRequests.add(request);
                request.setScheduledDropOff(time);
            }
            else {
                System.out.println("You cannot schedule a drop off for this request. It is not on the list of scheduled pick ups.");
            }
        }

        public double getCostofRoute(){
            double totalCost = 0;

            double lateDropoffs = 0;
            double durationCost = 0;

            // todo add all costs for the route

            for (Request request : requests){
                lateDropoffs += request.calcDropOffWait();
            }

            if (MAX_ROUTE_DURATION < routeDuration){
                durationCost = routeDuration - MAX_ROUTE_DURATION;
            }

            totalCost = lateDropoffs + durationCost; // todo add other costs, weighted costs

            return totalCost;
        }
    }

}