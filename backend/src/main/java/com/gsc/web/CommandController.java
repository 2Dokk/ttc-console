package com.gsc.web;

import com.gsc.command.Command;
import com.gsc.command.CommandService;
import com.gsc.command.CreateCommandRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Command create(@Valid @RequestBody CreateCommandRequest request) {
        return commands.create(request);
    }

    @GetMapping
    public List<Command> list(@RequestParam(defaultValue = "50") int limit) {
        return commands.recent(Math.min(limit, 500));
    }

    @PostMapping("/{id}/cancel")
    public Command cancel(@PathVariable long id) {
        return commands.cancel(id);
    }
}
