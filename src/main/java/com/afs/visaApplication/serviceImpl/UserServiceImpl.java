package com.afs.visaApplication.serviceImpl;

import com.afs.visaApplication.JWT.JwtFilter;
import com.afs.visaApplication.JWT.JwtUtil;
import com.afs.visaApplication.JWT.UsersDetailsService;
import com.afs.visaApplication.POJO.User;
import com.afs.visaApplication.constants.VisaConstants;
import com.afs.visaApplication.dao.UserDao;
import com.afs.visaApplication.service.UserService;
import com.afs.visaApplication.utils.EmailUtils;
import com.afs.visaApplication.utils.VisaUtils;
import com.afs.visaApplication.wrapper.UserWrapper;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
//import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    UserDao userDao;

    @Autowired
    UsersDetailsService usersDetailsService;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtFilter jwtFilter;

    @Autowired
    EmailUtils emailUtils;

    @Autowired
    AuthenticationManager authenticationManager;

    /*@Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }*/

    @Override
    public ResponseEntity<String> signUp(Map<String, String> requestMap) {
        try {
            if (validateSignUpMap(requestMap)) {
                User user = userDao.findByEmailId(requestMap.get("email"));
                if (Objects.isNull(user)) {
                    // Check password validation
                    if (validatePassword(requestMap.get("password"))) {
                        String encryptedPassword = passwordEncoder.encode(requestMap.get("password"));
                        requestMap.put("password", encryptedPassword);
                        userDao.save(getUserFromMap(requestMap));
                        logInteraction("User signed up", requestMap.get("email"));
                        return VisaUtils.getResponseEntity("Registration was successful", HttpStatus.OK);
                    } else {
                        return VisaUtils.getResponseEntity("Invalid password", HttpStatus.BAD_REQUEST);
                    }
                } else {
                    return VisaUtils.getResponseEntity("Email already in use", HttpStatus.BAD_REQUEST);
                }
            } else {
                return VisaUtils.getResponseEntity(VisaConstants.INVALID_DATA, HttpStatus.BAD_REQUEST);
            }
        }catch(Exception ex){
            ex.printStackTrace();
        }
        return VisaUtils.getResponseEntity(VisaConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private boolean validateSignUpMap(Map<String, String> requestMap){
        if(requestMap.containsKey("name") && requestMap.containsKey("email") && requestMap.containsKey("password")){
            return true;
        }
        return false;
    }

    private boolean validatePassword(String password) {
        // Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one special character
        return password != null && password.length() >= 8 &&
                password.matches(".*[A-Z].*") && // at least one uppercase letter
                password.matches(".*[a-z].*") && // at least one lowercase letter
                password.matches(".*\\d.*") && // at least one number
                password.matches(".*[!@#$%^&*()-_=+{};:,<.>/?\\|\\[\\]\"'].*"); // at least one special character
    }

    private User getUserFromMap(Map<String,String> requestMap){
        User user = new User();

        user.setName(requestMap.get("name"));
        user.setEmail(requestMap.get("email"));
        user.setPassword(requestMap.get("password"));
        user.setStatus("true");
        user.setRole("user");

        return user;
    }

    @Override
    public ResponseEntity<String> login(Map<String, String> requestMap) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(requestMap.get("email"), requestMap.get("password")));
                if (auth.isAuthenticated()) {
                    if (usersDetailsService.getUserDetail().getStatus().equalsIgnoreCase("true")) {
                        logInteraction("User logged in", requestMap.get("email"));
                        return new ResponseEntity<String>("{\"token\":\"" +
                                jwtUtil.generateToken(usersDetailsService.getUserDetail().getEmail(),
                                        usersDetailsService.getUserDetail().getRole()) + "\"}",
                                HttpStatus.OK);

                    } else {
                        return new ResponseEntity<String>("{\"message\":\"" + "Wait for admin approval." + "\"}", HttpStatus.BAD_REQUEST);
                    }
                }else{
                    return new ResponseEntity<String>("{\"message\":\"" + "Wrong password." + "\"}", HttpStatus.BAD_REQUEST);
                }


        }catch(Exception ex){
            ex.printStackTrace();
        }
        return new ResponseEntity<String>("{\"message\":\"" + "Bad Credentials" + "\"}", HttpStatus.BAD_REQUEST);
    }

    @Override
    public ResponseEntity<String> update(Map<String, String> requestMap) {
        try{
            if(jwtFilter.isBranchOfficial()){
                Optional<User> optional = userDao.findById(Integer.parseInt(requestMap.get("id")));
                if(!optional.isEmpty()){
                    userDao.updateStatus(requestMap.get("status"), Integer.parseInt(requestMap.get("id")));
                    sendMailToAllBranchOfficials(requestMap.get("status"), optional.get().getEmail(), userDao.getAllBranchOfficials());
                    logInteraction("Updated " + optional.get().getEmail() + " status to " + requestMap.get("status"), jwtFilter.getCurrentUser());
                    return VisaUtils.getResponseEntity("User Status Updated", HttpStatus.OK);
                }else{
                    return VisaUtils.getResponseEntity("User id does not exist", HttpStatus.OK);
                }
            }else{
                return VisaUtils.getResponseEntity(VisaConstants.UNAUTHORIZED_ACCESS, HttpStatus.UNAUTHORIZED);
            }
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return VisaUtils.getResponseEntity(VisaConstants.SOMETHING_WENT_WRONG, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void sendMailToAllBranchOfficials(String status, String user, List<String> allBranchOfficials) {
        allBranchOfficials.remove(jwtFilter.getCurrentUser());
        if(status != null && status.equalsIgnoreCase("false")){
            emailUtils.sendSimpleMessage(jwtFilter.getCurrentUser(), "Account blocked", "USER:- "+ user + " \n is blocked by \nBRANCH OFFICIAL:-" + jwtFilter.getCurrentUser(), allBranchOfficials);
        }else{
            emailUtils.sendSimpleMessage(jwtFilter.getCurrentUser(), "Account unblocked", "USER:- "+ user + " \n is unblocked by \nBRANCH OFFICIAL:-" + jwtFilter.getCurrentUser(), allBranchOfficials);
        }
    }

    @Override
    public ResponseEntity<List<UserWrapper>> getAllUser() {
        try{
            if(jwtFilter.isBranchOfficial()){
                return new ResponseEntity<>(userDao.getAllUser(), HttpStatus.OK);
            }else{
                return new ResponseEntity<>(new ArrayList<>(), HttpStatus.UNAUTHORIZED);
            }
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return new ResponseEntity<>(new ArrayList<>(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    User getMockUser(String email, String role, String status) {
        User user = new User();
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private void logInteraction(String interaction, String userEmail) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = sdf.format(new Date());

        String csvFile = "log_file.csv";
        try (FileWriter writer = new FileWriter(csvFile, true)) {
            writer.append(interaction)
                    .append(",")
                    .append(userEmail)
                    .append(",")
                    .append(timestamp)
                    .append("\n");
        } catch (IOException e) {
            log.error("Error writing to CSV file: " + e.getMessage());
        }
    }


}
