package com.example.airbnb.security;

import com.example.airbnb.Exceptions.ResourceNotFoundException;
import com.example.airbnb.Mapper.UserMapper;
import com.example.airbnb.dto.LoginDTO;
import com.example.airbnb.dto.SignUpRequestDTO;
import com.example.airbnb.dto.UserDto;
import com.example.airbnb.entity.User;
import com.example.airbnb.entity.enums.Role;
import com.example.airbnb.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JWTService jWTService;

    public UserDto signUp(SignUpRequestDTO signUpRequestDTO) {

        String username = signUpRequestDTO.username();

        if (userRepository.findByUsername(signUpRequestDTO.username()).isPresent()) {
            throw new RuntimeException("User already present with username: " + signUpRequestDTO.username());
        }

        User user = new User();
        user.setUsername(signUpRequestDTO.username());
        user.setPassword(passwordEncoder.encode(signUpRequestDTO.password()));
        user.setRole(Set.of(Role.GUEST));
        user = userRepository.save(user);

        return userMapper.toDto(user);
    }

    public String[] login(LoginDTO loginDTO) {

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginDTO.username(), loginDTO.password()));

        User user = (User) authentication.getPrincipal();

        String[] arr = new String[2];

        if (user != null) {
            arr[0] = jWTService.generateAccessToken(user);
            arr[1] = jWTService.generateRefreshToken(user);
        }
        return arr;

    }

    public String refreshToken(String refreshToken) {
        Long id = jWTService.getUserIdFromToken(refreshToken);

        User user = userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return jWTService.generateRefreshToken(user);
    }

}
