package net.nightwhistler.pageturner.login;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.parse.ParseException;
import com.parse.ParseUser;
import com.parse.SignUpCallback;

import net.nightwhistler.pageturner.R;

public class SignUpActivity extends Activity {
    EditText email,pass,userName;
    String emailtxt,passwordtxt, usernametxt;
    Button btnSignUp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up_screen);

        email=(EditText)findViewById(R.id.etEmail);
        pass=(EditText)findViewById(R.id.etPass);
        userName=(EditText)findViewById(R.id.etUserName);
        btnSignUp=(Button)findViewById(R.id.btnSingUp);

        btnSignUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                emailtxt=email.getText().toString();
                passwordtxt=pass.getText().toString();
                usernametxt=userName.getText().toString();
                // Force user to fill up the form
                if (usernametxt.equals("") && passwordtxt.equals("") && emailtxt.equals("")) {
                    Toast.makeText(getApplicationContext(),
                            "Please complete the sign up form",
                            Toast.LENGTH_LONG).show();

                } else {
                    // Save new user data into Parse.com Data Storage
                    ParseUser user = new ParseUser();
                    user.setEmail(emailtxt);
                    user.setUsername(usernametxt);
                    user.setPassword(passwordtxt);
                    user.signUpInBackground(new SignUpCallback() {
                        public void done(ParseException e) {
                            if (e == null) {
                                // Show a simple Toast message upon successful registration
                                Toast.makeText(getApplicationContext(),
                                        "Successfully Signed up, please log in.",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(getApplicationContext(),
                                        "Sign up Error", Toast.LENGTH_LONG)
                                        .show();
                            }
                        }
                    });
                }

            }
        });


    }


    
}
